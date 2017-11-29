package com.dataknown.flume.sink;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.apache.flume.Channel;
import org.apache.flume.Context;
import org.apache.flume.Event;
import org.apache.flume.EventDeliveryException;
import org.apache.flume.Transaction;
import org.apache.flume.conf.Configurable;
import org.apache.flume.sink.AbstractSink;
import org.postgresql.copy.CopyManager;
import org.postgresql.core.BaseConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

public class MyGPSinkPan extends AbstractSink implements Configurable{
	private Logger logger = LoggerFactory.getLogger(MyGPSinkPan.class);
	private String hostname; // 主机名
	private String port; // 端口
	private String databaseName; // 数据库名字
	private String tableName; // 表名
	private String user; // 用户名
	private String password; // 密码
	private int batchSize; // 每次数据库交互， 处理多少条数据
	private Connection conn;
	private static int count = 0;
	
	private List<String> lines = new ArrayList<String>();
	
	private String regex;     // 正则
	private Pattern pattern;
	private static final String FIELDS_DELIMITER = "\001";   // 流中字段的分隔符
	private int batchTimeout;   // 提交超时时间
	private static final int DEFAULT_BATCH_TIMEOUT = 3000;
	
	private ByteArrayOutputStream out = null;
	
	private ScheduledExecutorService timedFlushExecService;
	private ScheduledFuture<?> future;
	private volatile boolean isInterrupted;
	private static Runnable runnable;

	public MyGPSinkPan() {
		logger.info("MyGPSinkPan start...");
	}

	public void configure(Context context) {
		hostname = context.getString("hostname");
		Preconditions.checkNotNull(hostname, "hostname must be set!!");

		port = context.getString("port");
		Preconditions.checkNotNull(port, "port must be set!!");

		databaseName = context.getString("databaseName");
		Preconditions.checkNotNull(databaseName, "databaseName must be set!!");

		tableName = context.getString("tableName");
		Preconditions.checkNotNull(tableName, "tableName must be set!!");

		user = context.getString("user");
		Preconditions.checkNotNull(user, "user must be set!!");

		password = context.getString("password");
		Preconditions.checkNotNull(password, "password must be set!!");

		batchSize = context.getInteger("batchSize", 100);
		Preconditions.checkArgument(batchSize > 0,
				"batchSize must be a positive number!!");
		
		regex = context.getString("regex");
		Preconditions.checkNotNull(regex, "regex must be not none!!");
		
		batchTimeout = context.getInteger("batchTimeout", DEFAULT_BATCH_TIMEOUT);
	}

	@Override
	public void start() {
		super.start();
		try {
			Class.forName("org.postgresql.Driver");
		} catch (ClassNotFoundException e) {
			e.printStackTrace();
		}

		String url = "jdbc:postgresql://" + hostname + ":" + port + "/" + databaseName;

		try {
			conn = DriverManager.getConnection(url, user, password);
			conn.setAutoCommit(false);
		} catch (SQLException e) {
			e.printStackTrace();
			System.exit(1);
		}
		
		try {
			pattern = Pattern.compile(regex);
		} catch (PatternSyntaxException e) {
			e.printStackTrace();
			logger.error("invalid regex");
			System.exit(1);
		}
		
		out = new ByteArrayOutputStream();
		timedFlushExecService = Executors.newSingleThreadScheduledExecutor(
				new ThreadFactoryBuilder().setNameFormat(
				 "timedFlushExecService" + 
				Thread.currentThread().getId() + "-%d").build());
		isInterrupted = false;
		runnable = new Runnable() {
			public void run() {
				doCopy();
			}
		};
	}

	@Override
	public void stop() {
		super.stop();
		if (out != null) {
			try {
				out.flush();
				out.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		if (conn != null) {
			try {
				conn.close();
			} catch (SQLException e) {
				e.printStackTrace();
			}
		}
		if(timedFlushExecService != null) {
			timedFlushExecService.shutdown();
//			while (!timedFlushExecService.isTerminated()) {
//                try {
//                	timedFlushExecService.awaitTermination(500, TimeUnit.MILLISECONDS);
//                } catch (InterruptedException e) {
//                  logger.warn("Interrupted while waiting for exec executor service "
//                    + "to stop. Just exiting.");
//                  Thread.currentThread().interrupt();
//                }
//			}
		}
	}

	public Status process() throws EventDeliveryException {
		
		Preconditions.checkNotNull(out, "OutputStream should be opened, but now is closed.");
		
		Status result = Status.READY;
		Channel channel = getChannel();
		Transaction transaction = channel.getTransaction();
		Event event;
		String content;
		
		if(!lines.isEmpty()) {
			lines.clear();
		}
		transaction.begin();
		
		
		try {
			
			for (int i = 0; i < batchSize; i++) {
				future = timedFlushExecService.schedule(runnable, batchTimeout, TimeUnit.MILLISECONDS);
				event = channel.take();
				future.cancel(false);
				if (event != null) {
					content = new String(event.getBody());
					Matcher m = pattern.matcher(content);
					if(m.find()) {
						for(int j=1, cnt=m.groupCount();j<=cnt;j++) {
							lines.add(m.group(j));
						}
						out.write(pin(lines).getBytes());
						lines.clear();
					}
				} else {
					result = Status.BACKOFF;
					break;
				}
			}
			
			doCopy();
			
			if(isInterrupted) {
				isInterrupted = false;
				throw new Exception("Failed when copying data into greenplum");
			}
			
			conn.commit();
			transaction.commit();
		} catch (Throwable e) {
			try {
				transaction.rollback();
			} catch (Exception e2) {
				logger.error(
						"Exception in rollback. Rollback might not have been"
								+ "successful.", e2);
			}
			logger.error("Failed to commit transaction."
					+ "Transaction rolled back.", e);
			Throwables.propagate(e);
		} finally {
			if(transaction != null){
				transaction.close();
			}
			out.reset();
		}
		return result;
	}
	
	/**
	 * 尝试向数据库提交 copy 操作，出现异常时改变 isInterrupted 状态
	 * 
	 */
	private void doCopy(){
		if(out.size()>0) {
			synchronized (out) {
				if(out.size()>0) {
					try {
						logger.info("------> begin to process ↓");
						long status = copyFromStream(); 
//						long status = copyToFile();
						out.reset();
						logger.info("MyGPSinkPan process："+(++count)+" status："+status);
					} catch (Exception e) {
						logger.error(e.getMessage());
						isInterrupted = true;
					} 
				}
			}
		}
	}
	
	/**
	 * 使用\001 作为分隔符，连接字符串
	 * @param strs 字符串集合
	 * @return 连接后的字符串
	 */
	public static String pin(List<String> strs){
		StringBuilder sb = new StringBuilder();
		// postgresql 使用COPY读取 TEXT 文件，分隔符细节：
		// https://www.postgresql.org/docs/9.4/static/sql-copy.html
		// 以 \n 分隔，若字段中存在 \n 需要做转义 \\n
		int size = strs.size();
		for (int i = 0; i < size-1; i++) {
			sb.append(join("\\n", strs.get(i).split("\\n")));
			sb.append(FIELDS_DELIMITER);
		}
		sb.append(join("\\n", strs.get(size-1).split("\\n")));
		sb.append("\n");
		return sb.toString();
	}
	
	/**
	 * String.join 是jdk 1.8的方法，为防止版本不兼容，这里实现了一个
	 * 
	 * @param delimiter  分隔符
	 * @param elements   待连接的
	 * @return
	 */
	public static String join(String delimiter, String... elements) {
		StringBuilder sb = new StringBuilder();
		int len = elements.length;
		for(int i=0;i<len-1;i++) {
			sb.append(elements[i])
			  .append(delimiter);
		}
		sb.append(elements[len-1]);
		return sb.toString();
	}
	
	/**
	 * 使用 COPY 方法向greenplum导入数据
	 * 
	 * @return 导入的数据量
	 * @throws SQLException
	 * @throws IOException
	 */
	public long copyFromStream()   
	        throws SQLException, IOException {  
	      
		ByteArrayInputStream byteArrayInputStream = null; 
		
	    try {  
	        CopyManager copyManager = new CopyManager((BaseConnection)conn);  
	        byteArrayInputStream = new ByteArrayInputStream(out.toByteArray());
            return copyManager.copyIn(String.format("COPY %s FROM STDIN with delimiter '%s' null as ''", tableName, FIELDS_DELIMITER), byteArrayInputStream);
	    } finally {  
	        if (byteArrayInputStream != null) {  
	            try {  
	            	byteArrayInputStream.close();  
	            } catch (IOException e) {  
	                e.printStackTrace();  
	            }  
	        }  
	    }  
	}
	
	/**
	 * 将流中的数据导出到文件中
	 * 测试使用的方法，可删除
	 * 
	 * @return 导出的数据量
	 * @throws Exception
	 */
	public long copyToFile() throws Exception{
		FileOutputStream fileOutputStream = new FileOutputStream(new File("/root/flume/tmp/ooout.txt"));
		long status = out.size();
		fileOutputStream.write(out.toByteArray());
		if(fileOutputStream!=null) {
			fileOutputStream.close();
		}
		return status;
	}
	
	public static void main(String[] args) {
//		String str = "198206984 2017-08-27 00:01:59,228 [DubboServerHandler-192.168.6.107:20880-thread-199] INFO  c.c.e.o.w.common.ext.ThreadHolder - ";
		String str = "198206954 2017-08-27 00:01:59,198 [DubboServerHandler-192.168.6.107:20880-thread-199] INFO  c.c.e.o.w.m.i.RealtimeManagerImpl - 获取到的工作日为:20170828";    
		//String str = "198949380 2017-08-27 00:14:21,624 [DubboServerHandler-192.168.6.107:20880-thread-199] INFO  c.c.e.o.w.common.util.ResultHelper - public com.cmf.ec.order.facade.output.account.InverstorRecognitionResult com.cmf.ec.order.webapp.service.AccountServiceImpl.queryInvestorRegister(java.lang.String),baseresult: {\"cmfUserId\":\"20140826000043040542\",\"errCode\":\"0000\",\"errMsg\":\"系统调用成功\",\"isRegister\":\"Y\",\"subErrCode\":\"0000\",\"subErrMsg\":\"系统调用成功\"}";
		
		Pattern pattern = Pattern.compile("^(\\S+ \\S+ \\S+:\\S+:\\S+,\\S+)\\s+\\[(\\S+)\\]\\s+(DEBUG|ERROR|INFO|WARN)\\s*(\\S+)\\s*-\\s*(\\S*[\\s\\S*]+)");
		Matcher m = pattern.matcher(str);
		if(m.find()) {
			for(int i=1, count=m.groupCount();i<=count;i++) {
				System.out.println(i+":"+m.group(i));
			}
		}
		
		str = "316891095 2017-08-28 09:00:03,339 [DubboServerHandler-192.168.6.107:20880-thread-198] WARN  c.c.e.o.w.service.TradeServiceImpl - exception occur when convert,  input={\"channelId\":\"ECAPP\",\"fundId\":\"217004\",\"largeFlg\":\"1\",\"otradeAcco\":\"01090531\",\"redAmt\":\"500\",\"serialNo\":\"59A36B130006D86B\",\"toFundId\":\"217005\",\"tradeAcco\":\"01090531\"}\r\n" + 
				"com.cmf.ec.order.webapp.common.util.ApiException: 9108:¸ÃÕÊºÅ¿ÉÓÃÓà¶î²»×ã\r\n" + 
				"        at com.cmf.ec.order.webapp.manager.impl.TradeManagerImpl$19.doInTransactionWithoutResult(TradeManagerImpl.java:3624) ~[TradeManagerImpl$19.class:na]\r\n" + 
				"        at org.springframework.transaction.support.TransactionCallbackWithoutResult.doInTransaction(TransactionCallbackWithoutResult.java:33) ~[TransactionCallbackWithoutResult.class:3.2.3.RELEASE]\r\n" + 
				"        at org.springframework.transaction.support.TransactionTemplate.execute(TransactionTemplate.java:131) ~[TransactionTemplate.class:3.2.3.RELEASE]\r\n" + 
				"        at com.cmf.ec.order.webapp.manager.impl.TradeManagerImpl.doConv(TradeManagerImpl.java:3617) ~[TradeManagerImpl.class:na]\r\n" + 
				"        at com.cmf.ec.order.webapp.manager.impl.TradeManagerImpl.convert(TradeManagerImpl.java:3488) ~[TradeManagerImpl.class:na]\r\n" + 
				"        at sun.reflect.NativeMethodAccessorImpl.invoke0(Native Method) ~[na:1.6.0_45]\r\n" + 
				"        at sun.reflect.NativeMethodAccessorImpl.invoke(NativeMethodAccessorImpl.java:39) ~[na:1.6.0_45]\r\n" + 
				"        at sun.reflect.DelegatingMethodAccessorImpl.invoke(DelegatingMethodAccessorImpl.java:25) ~[na:1.6.0_45]\r\n" + 
				"        at java.lang.reflect.Method.invoke(Method.java:597) ~[na:1.6.0_45]\r\n" + 
				"        at org.springframework.aop.support.AopUtils.invokeJoinpointUsingReflection(AopUtils.java:317) ~[AopUtils.class:3.2.3.RELEASE]\r\n" + 
				"        at org.springframework.aop.framework.ReflectiveMethodInvocation.invokeJoinpoint(ReflectiveMethodInvocation.java:183) [ReflectiveMethodInvocation.class:3.2.3.RELEASE]\r\n" + 
				"        at org.springframework.aop.framework.ReflectiveMethodInvocation.proceed(ReflectiveMethodInvocation.java:150) [ReflectiveMethodInvocation.class:3.2.3.RELEASE]\r\n" + 
				"        at com.cmf.ec.commons.annotation.LazyRefreshCacheInterceptor.invokeUnderTrace(LazyRefreshCacheInterceptor.java:57) ~[LazyRefreshCacheInterceptor.class:na]\r\n" + 
				"        at org.springframework.aop.interceptor.AbstractTraceInterceptor.invoke(AbstractTraceInterceptor.java:111) [AbstractTraceInterceptor.class:3.2.3.RELEASE]\r\n" + 
				"        at org.springframework.aop.framework.ReflectiveMethodInvocation.proceed(ReflectiveMethodInvocation.java:172) [ReflectiveMethodInvocation.class:3.2.3.RELEASE]\r\n" + 
				"        at com.qgo.profiler.connector.ProfilerInterceptor.invokeUnderTrace(ProfilerInterceptor.java:36) [ProfilerInterceptor.class:na]\r\n" + 
				"        at org.springframework.aop.interceptor.AbstractTraceInterceptor.invoke(AbstractTraceInterceptor.java:111) [AbstractTraceInterceptor.class:3.2.3.RELEASE]\r\n" + 
				"        at org.springframework.aop.framework.ReflectiveMethodInvocation.proceed(ReflectiveMethodInvocation.java:172) [ReflectiveMethodInvocation.class:3.2.3.RELEASE]\r\n" + 
				"        at org.springframework.aop.framework.JdkDynamicAopProxy.invoke(JdkDynamicAopProxy.java:204) ~[JdkDynamicAopProxy.class:3.2.3.RELEASE]";
		

//		System.out.println(String.join("\\n", str.split("\\r\\n")));  // jdk 1.8， 集群环境1.7,不能使用String.join
//		System.out.println(join("\\n", str.split("\\r\\n")));
		System.out.println("ok\nfine".contains("\n"));
		
	}
	
}
