package edu.virginia.vcgr.genii.container.replicatedExport;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import edu.virginia.vcgr.genii.container.db.DatabaseConnectionPool;
import edu.virginia.vcgr.genii.container.resource.IResourceKeyTranslater;
import edu.virginia.vcgr.genii.container.resource.db.BasicDBResourceFactory;

public class SharedRExportBaseFactory extends BasicDBResourceFactory
{
	static private final String _CREATE_REXPORT_TABLE_STMT =
		"CREATE TABLE rexport " +
		"(dirid VARCHAR(40) PRIMARY KEY, path VARCHAR(512), parentIds VARCHAR(4096))";
	static private final String _CREATE_REXPORT_ENTRY_TABLE_STMT =
		"CREATE TABLE rexportentry " +
		"(dirid VARCHAR(40), name VARCHAR(256), endpoint BLOB (128K), " +
		"entryid VARCHAR(40), type CHAR(1), " +
		"CONSTRAINT rexportentryconstraint1 PRIMARY KEY (dirid, name))";
	static private final String _CREATE_REXPORT_ATTR_TABLE_STMT =
		"CREATE TABLE rexportentryattr " +
		"(entryid VARCHAR(40) PRIMARY KEY, attr VARCHAR (8192) FOR BIT DATA)";
	
	static private final String _CREATE_RESOURCE_TO_RESOLVER_MAPPING_TABLE_STMT =
		"CREATE TABLE resolvermapping " +
		"(resourceEPI VARCHAR(60) PRIMARY KEY, resolverEPI VARCHAR(60), " +
		"resolverEPR BLOB (128K))";
	
	protected SharedRExportBaseFactory(
			DatabaseConnectionPool pool, 
			IResourceKeyTranslater translator)
		throws SQLException
	{
		super(pool, translator);
	}

	protected void createTables() throws SQLException
	{
		Connection conn = null;
		Statement stmt = null;
		
		super.createTables();
		
		try{
			conn = _pool.acquire();
			stmt = conn.createStatement();
			
			stmt.executeUpdate(_CREATE_REXPORT_TABLE_STMT);
			stmt.executeUpdate(_CREATE_REXPORT_ENTRY_TABLE_STMT);
			stmt.executeUpdate(_CREATE_REXPORT_ATTR_TABLE_STMT);
			stmt.executeUpdate(_CREATE_RESOURCE_TO_RESOLVER_MAPPING_TABLE_STMT);
			conn.commit();
		}
		catch (SQLException sqe){
			//assume the table already exists.
		}
		finally{
			if (stmt != null)
				try { stmt.close(); } catch (SQLException sqe) {}
			if (conn != null)
				_pool.release(conn);
		}
	}
}