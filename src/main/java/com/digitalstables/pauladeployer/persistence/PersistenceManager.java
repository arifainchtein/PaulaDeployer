package com.digitalstables.pauladeployer.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import com.digitalstables.pauladeployer.utils.Utils;

// The same local Postgres PaulaUploader's DeploymentStore already uses on this Pi (paulauploader
// db - see provision-pi.sh) - not a new database, just a new table in it (deployAttempt). One-time
// setup (not automated - run once on this Pi, see PaulaDeployer's own README/design doc note):
//
//   psql paulauploader -c "create table deployAttempt(id serial primary key, manifestfile
//     varchar(200) not null, productid int, productname varchar(100), serialnumber varchar(50),
//     reponame varchar(100), version int, startedon bigint, completedon bigint, status
//     varchar(20) default 'Running', terminallog text, reported boolean default false,
//     productdefinitionid int);"
//
// productdefinitionid added 2026-09-05 for the "Confirm" footer button (ConfirmUpgradesProcessingHandler) -
// nullable, since only manifests built after the matching factory webapp fix (SendDeployPackageProcessingHandler
// now includes productDefinitionId) carry it; older/in-flight attempts just can't be auto-confirmed.
//   psql paulauploader -c "alter table deployAttempt add column if not exists productdefinitionid int;"
//
// Same connection/PreparedStatement/manual-close style as the factory webapp's
// PostgresqlPersistenceManager and PaulaUploader's DeploymentStore, for consistency.
public class PersistenceManager{

	private static final Logger logger = LogManager.getLogger("PersistenceManager");

	private static PersistenceManager instance;
	private final ConnectionPool connectionPool;

	private PersistenceManager(){
		connectionPool = new ConnectionPool();
		connectionPool.setDriverClassName("org.postgresql.Driver");
		connectionPool.setUrl("jdbc:postgresql://127.0.0.1:5432/paulauploader");
		connectionPool.setUsername("paulauploader");
		connectionPool.setPassword("paulauploader");
		connectionPool.setMaxTotal(5);
		connectionPool.setInitialSize(1);
		logger.info("Connection pool configured for jdbc:postgresql://127.0.0.1:5432/paulauploader");
	}

	public static synchronized PersistenceManager instance(){
		if(instance == null) instance = new PersistenceManager();
		return instance;
	}

	// Starts a new attempt row (status Running) and returns its id - the "attemptId" the frontend
	// polls with (GetDeployStatus) while the flash runs in the background.
	public int startAttempt(String manifestFile, int productId, String productName, String serialNumber, String repoName, int version, int productDefinitionId){
		String sql = "insert into deployAttempt(manifestfile, productid, productname, serialnumber, reponame, version, startedon, status, productdefinitionid) "
				+ "values(?,?,?,?,?,?,?,'Running',?) returning id";
		Connection connection = null;
		PreparedStatement preparedStatement = null;
		ResultSet rs = null;
		int id = -1;
		try{
			connection = connectionPool.getConnection();
			preparedStatement = connection.prepareStatement(sql);
			preparedStatement.setString(1, manifestFile);
			preparedStatement.setInt(2, productId);
			preparedStatement.setString(3, productName);
			preparedStatement.setString(4, serialNumber);
			preparedStatement.setString(5, repoName);
			preparedStatement.setInt(6, version);
			preparedStatement.setLong(7, System.currentTimeMillis());
			// 0/negative means "manifest predates the productDefinitionId field" - store NULL, not
			// a fake id, so ConfirmUpgradesProcessingHandler can tell "nothing to confirm" apart
			// from "id 0" (Postgres serial ids never start at 0 anyway).
			if(productDefinitionId > 0){
				preparedStatement.setInt(8, productDefinitionId);
			}else{
				preparedStatement.setNull(8, java.sql.Types.INTEGER);
			}
			rs = preparedStatement.executeQuery();
			if(rs.next()) id = rs.getInt(1);
			logger.info("Started deployAttempt id=" + id + " manifestFile=" + manifestFile + " product=" + productName);
		}catch(SQLException e){
			logger.warn(Utils.getStringException(e));
		}finally{
			closeQuietly(rs, preparedStatement, connection);
		}
		return id;
	}

	// Appends one line to this attempt's running terminal log - called once per line as the flash
	// produces output, so a poller sees live progress rather than one dump at the end.
	public void appendLog(int attemptId, String line){
		String sql = "update deployAttempt set terminallog = coalesce(terminallog,'') || ? || chr(10) where id=?";
		Connection connection = null;
		PreparedStatement preparedStatement = null;
		try{
			connection = connectionPool.getConnection();
			preparedStatement = connection.prepareStatement(sql);
			preparedStatement.setString(1, line);
			preparedStatement.setInt(2, attemptId);
			preparedStatement.executeUpdate();
			logger.trace("deployAttempt id=" + attemptId + " log line: " + line);
		}catch(SQLException e){
			logger.warn(Utils.getStringException(e));
		}finally{
			closeQuietly(null, preparedStatement, connection);
		}
	}

	public void completeAttempt(int attemptId, boolean success){
		String sql = "update deployAttempt set status=?, completedon=? where id=?";
		Connection connection = null;
		PreparedStatement preparedStatement = null;
		try{
			connection = connectionPool.getConnection();
			preparedStatement = connection.prepareStatement(sql);
			preparedStatement.setString(1, success ? "Success" : "Failed");
			preparedStatement.setLong(2, System.currentTimeMillis());
			preparedStatement.setInt(3, attemptId);
			preparedStatement.executeUpdate();
			logger.info("deployAttempt id=" + attemptId + " completed, success=" + success);
		}catch(SQLException e){
			logger.warn(Utils.getStringException(e));
		}finally{
			closeQuietly(null, preparedStatement, connection);
		}
	}

	public JSONObject getAttempt(int attemptId){
		String sql = "select id, manifestfile, productid, productname, serialnumber, reponame, version, "
				+ "startedon, completedon, status, terminallog, productdefinitionid, reported from deployAttempt where id=?";
		Connection connection = null;
		PreparedStatement preparedStatement = null;
		ResultSet rs = null;
		JSONObject toReturn = null;
		try{
			connection = connectionPool.getConnection();
			preparedStatement = connection.prepareStatement(sql);
			preparedStatement.setInt(1, attemptId);
			rs = preparedStatement.executeQuery();
			if(rs.next()) toReturn = rowToJson(rs);
		}catch(SQLException e){
			logger.warn(Utils.getStringException(e));
		}finally{
			closeQuietly(rs, preparedStatement, connection);
		}
		return toReturn;
	}

	// Most recent attempt for a given manifest file - drives the main screen's tile color
	// (yellow=never attempted, green=last attempt succeeded, red=last attempt failed; a currently
	// Running attempt is what GetDeployQueueProcessingHandler uses to keep a tile disabled).
	public JSONObject getLatestAttemptForManifest(String manifestFile){
		String sql = "select id, manifestfile, productid, productname, serialnumber, reponame, version, "
				+ "startedon, completedon, status, terminallog, productdefinitionid, reported from deployAttempt where manifestfile=? order by startedon desc limit 1";
		Connection connection = null;
		PreparedStatement preparedStatement = null;
		ResultSet rs = null;
		JSONObject toReturn = null;
		try{
			connection = connectionPool.getConnection();
			preparedStatement = connection.prepareStatement(sql);
			preparedStatement.setString(1, manifestFile);
			rs = preparedStatement.executeQuery();
			if(rs.next()) toReturn = rowToJson(rs);
		}catch(SQLException e){
			logger.warn(Utils.getStringException(e));
		}finally{
			closeQuietly(rs, preparedStatement, connection);
		}
		return toReturn;
	}

	// Feeds the "Confirm" footer button (ConfirmUpgradesProcessingHandler) - successful attempts not
	// yet reported back to the factory server as a confirmed upgrade. Only Success (a Failed flash
	// has nothing to confirm) and only rows with a productdefinitionid (older manifests, from before
	// the factory webapp's SendDeployPackageProcessingHandler started including it, can't be
	// auto-confirmed at all - see this class's own header comment).
	public JSONArray getUnreportedAttempts(){
		String sql = "select id, manifestfile, productid, productname, serialnumber, reponame, version, "
				+ "startedon, completedon, status, terminallog, productdefinitionid, reported from deployAttempt "
				+ "where reported=false and status='Success' and productdefinitionid is not null";
		Connection connection = null;
		PreparedStatement preparedStatement = null;
		ResultSet rs = null;
		JSONArray toReturn = new JSONArray();
		try{
			connection = connectionPool.getConnection();
			preparedStatement = connection.prepareStatement(sql);
			rs = preparedStatement.executeQuery();
			while(rs.next()) toReturn.put(rowToJson(rs));
		}catch(SQLException e){
			logger.warn(Utils.getStringException(e));
		}finally{
			closeQuietly(rs, preparedStatement, connection);
		}
		return toReturn;
	}

	public void markReported(int attemptId){
		String sql = "update deployAttempt set reported=true where id=?";
		Connection connection = null;
		PreparedStatement preparedStatement = null;
		try{
			connection = connectionPool.getConnection();
			preparedStatement = connection.prepareStatement(sql);
			preparedStatement.setInt(1, attemptId);
			preparedStatement.executeUpdate();
		}catch(SQLException e){
			logger.warn(Utils.getStringException(e));
		}finally{
			closeQuietly(null, preparedStatement, connection);
		}
	}

	private JSONObject rowToJson(ResultSet rs) throws SQLException{
		JSONObject obj = new JSONObject();
		obj.put("id", rs.getInt(1));
		obj.put("manifestfile", rs.getString(2));
		obj.put("productid", rs.getInt(3));
		obj.put("productname", rs.getString(4));
		obj.put("serialnumber", rs.getString(5));
		obj.put("reponame", rs.getString(6));
		obj.put("version", rs.getInt(7));
		obj.put("startedon", rs.getLong(8));
		obj.put("completedon", rs.getLong(9));
		obj.put("status", rs.getString(10));
		String terminallog = rs.getString(11);
		obj.put("terminallog", terminallog == null ? "" : terminallog);
		int productDefinitionId = rs.getInt(12);
		if(!rs.wasNull()) obj.put("productdefinitionid", productDefinitionId);
		obj.put("reported", rs.getBoolean(13));
		return obj;
	}

	private void closeQuietly(ResultSet rs, PreparedStatement preparedStatement, Connection connection){
		if(rs != null){ try{ rs.close(); }catch(SQLException e){ /* ignore */ } }
		if(preparedStatement != null){ try{ preparedStatement.close(); }catch(SQLException e){ /* ignore */ } }
		if(connection != null){ try{ connectionPool.closeConnection(connection); }catch(SQLException e){ /* ignore */ } }
	}

}
