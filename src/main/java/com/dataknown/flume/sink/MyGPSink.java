package com.dataknown.flume.sink;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.Lists;
/**
 * 
 * @author czc
 *
 */
public class MyGPSink extends AbstractSink implements Configurable {
	private Logger logger = LoggerFactory.getLogger(MyGPSink.class);
	private String hostname; // 主机名
	private String port; // 端口
	private String databaseName; // 数据库名字
	private String tableName; // 表名
	private String user; // 用户名
	private String password; // 密码
	private int batchSize; // 每次数据库交互， 处理多少条数据
	private Connection conn;
	private PreparedStatement preparedStatement;
	private int count = 0;
	private static final String DRIVER_NAME = "org.postgresql.Driver";
//	private static int distribute_id = 0;
	
	private static final String DEFAULT_REGEX = "^(\\S+ \\S+ \\S+ \\S+:\\S+:\\S+,\\S+)\\s+(DEBUG|ERROR|INFO|WARN)\\s*\\[(\\S+)\\]\\s+\\((\\S+\\.\\S+:\\S+)\\)\\s*-\\s*([\\s\\S]*)$";
	private String regex;     // 正则
	private Pattern pattern;

	public MyGPSink() {
		logger.info("MyGPSink start...");
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
//		Preconditions.checkNotNull(batchSize > 0,
//				"batchSize must be a positive number!!");
		Preconditions.checkState(batchSize > 0, 
				"batchSize must be a positive number!!");
		
		regex = context.getString("regex", DEFAULT_REGEX);
		Preconditions.checkNotNull(regex, "regex must be not none!!");
	}

	@Override
	public void start() {
		super.start();
		try {
			Class.forName(DRIVER_NAME);
		} catch (ClassNotFoundException e) {
			e.printStackTrace();
		}

//		String url = "jdbc:mysql://" + hostname + ":" + port + "/"
//				+ databaseName + "?Unicode=true&characterEncoding=UTF-8";
		String url = "jdbc:postgresql://" + hostname + ":" + port + "/" + databaseName;
//		String url = "jdbc:pivotal:greenplum://" + hostname + ":" + port + ";" + "DatabaseName=" +  databaseName;

		try {
			conn = DriverManager.getConnection(url, user, password);
			conn.setAutoCommit(false);
			
			preparedStatement = conn.prepareStatement("insert into "
					+ tableName + " (date_time,log_type,thread_name,class_name,info) values (?,?,?,?,?)");

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
	}
	
	public void stop() {
		super.stop();
		if (preparedStatement != null) {
			try {
				preparedStatement.close();
			} catch (SQLException e) {
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
	}

	public Status process() throws EventDeliveryException {
		
		Status result = Status.READY;
		Channel channel = getChannel();
		Transaction transaction = channel.getTransaction();
		Event event;
		String content;

		List<String> actions = Lists.newArrayList();
		transaction.begin();

		try {
			for (int i = 0; i < batchSize; i++) {
				event = channel.take();
				if (event != null) {
					content = new String(event.getBody());
					actions.add(content);
				} else {
					result = Status.BACKOFF;
					break;
				}
			}

            /* 核心代码 */
			if (actions.size() > 0) {
				preparedStatement.clearBatch();
				for (String temp : actions) {
					// 通过正则去抽取每个字段， 并插入表中
					Matcher m = pattern.matcher(temp);
					if(m.find()) {
						for(int i=1, count=m.groupCount();i<=count;i++) {
							preparedStatement.setString(i, m.group(i));
						}
						preparedStatement.addBatch();
					}else {
						logger.info(String.format("当前正则无法匹配\"%s\"",temp));
					}
				}
				preparedStatement.executeBatch();

				conn.commit();
			}
			logger.info("MyGPSink process："+(++count)+" actions"+ actions.size());
			
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
			transaction.close();
		}
		return result;
	}
}
