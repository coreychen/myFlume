package com.dataknown.flume.sink;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

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
public class MySimpleGPSink extends AbstractSink implements Configurable {
	private Logger logger = LoggerFactory.getLogger(MySimpleGPSink.class);
	private String hostname; // 主机名
	private String port; // 端口
	private String databaseName; // 数据库名字
	private String tableName; // 表名
	private String user; // 用户名
	private String password; // 密码
	private int batchSize; // 每次数据库交互， 处理多少条数据
	private Connection conn;
	private PreparedStatement preparedStatement;
	private static int count = 0;
	private static int DISTRIBUTED_id = 0;

	public MySimpleGPSink() {
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
		Preconditions.checkNotNull(batchSize > 0,
				"batchSize must be a positive number!!");
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

			preparedStatement = conn.prepareStatement("insert into "
					+ tableName + " (content) values (?)");
		} catch (SQLException e) {
			e.printStackTrace();
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

			if (actions.size() > 0) {
				preparedStatement.clearBatch();
				for (String temp : actions) {
					preparedStatement.setString(1, temp);
					preparedStatement.setInt(2, DISTRIBUTED_id);
					preparedStatement.addBatch();
				}
				preparedStatement.executeBatch();
				conn.commit();
				preparedStatement.clearBatch();
				DISTRIBUTED_id++;
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
