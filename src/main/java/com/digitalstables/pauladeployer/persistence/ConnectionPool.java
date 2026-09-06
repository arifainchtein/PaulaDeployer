package com.digitalstables.pauladeployer.persistence;

import java.sql.Connection;
import java.sql.SQLException;

import org.apache.commons.dbcp2.BasicDataSource;

public class ConnectionPool extends BasicDataSource{

	public Connection getConnection() throws SQLException{
		return super.getConnection();
	}

	public void closeConnection(Connection con) throws SQLException{
		con.close();
	}

}
