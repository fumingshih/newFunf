/**
 * 
 * Funf: Open Sensing Framework
 * Copyright (C) 2010-2011 Nadav Aharony, Wei Pan, Alex Pentland.
 * Acknowledgments: Alan Gardner
 * Contact: nadav@media.mit.edu
 * 
 * This file is part of Funf.
 * 
 * Funf is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 * 
 * Funf is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with Funf. If not, see <http://www.gnu.org/licenses/>.
 * 
 */
package edu.mit.media.funf.storage;



import java.io.File;
import java.util.HashMap;
import java.util.Map;

import android.app.IntentService;
import android.content.Intent;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Binder;
import android.os.Environment;
import android.os.IBinder;
import android.util.Log;
import edu.mit.media.funf.util.FileUtil;
import edu.mit.media.funf.util.LogUtil;

/**
 * Simple database service that is able to write timestamp, name, value tuples.
 * 
 * There is a single separate thread responsible for all writes and maintenance, 
 * so there is no need to deal with synchronization.  
 * 
 * This class can be subclassed to provide custom implementations of Archive and 
 * SQliteHelper.Subclasses have no access to data queue.
 * @author alangardner
 * 
 * Add abstract method exportDB
 * @Author fumingshih
 */
public abstract class DatabaseService extends IntentService {

	
	public static final String
		ACTION_RECORD = "edu.mit.media.funf.RECORD",
		ACTION_ARCHIVE = "edu.mit.media.funf.ARCHIVE",
		ACTION_CLEAR_BACKUP = "CLEAR_BACKUP",
		ACTION_EXPORT = "EXPORT"; //export the db file to specific format, for example CSV
	
	public static final String DATABASE_NAME_KEY = "DATABASE_NAME";
	private static final String TAG = "DatabaseService";
	
	private Map<String, SQLiteOpenHelper> databaseHelpers; // Cache open database helpers

	public DatabaseService() {
		super("FunfDatabaseService"); // Name only used for debugging
	}
	
	@Override
	public void onDestroy() {
		super.onDestroy();
		if (databaseHelpers != null) {
			for (SQLiteOpenHelper dbHelper : databaseHelpers.values()) {
				dbHelper.close();
			}
		}
		Log.i(LogUtil.TAG, "Destroyed");
	}

	@Override
	public void onHandleIntent(Intent intent) {
		Log.i(LogUtil.TAG, "Started");
		final String databaseName = intent.getStringExtra(DATABASE_NAME_KEY);
		if (databaseName == null) {
			Log.e(LogUtil.TAG, "Database name not specified.");
			return;
		}
		String action = intent.getAction();
		if (action == null || action.equals(ACTION_RECORD)) {
			writeToDatabase(databaseName, intent);
		} else if (action.equals(ACTION_ARCHIVE)) {
			runArchive(databaseName);
		} else if (action.equals(ACTION_EXPORT)) {
			exportDatabase(databaseName, intent);
		} else if (action.equals(ACTION_CLEAR_BACKUP)){
			clearBackup(databaseName, intent);
		}
	}
	
	/**
	 * Returns the file archive for the database.
	 * @param databaseName
	 * @return
	 */
	public FileArchive getArchive(String databaseName) {
		return DefaultArchive.getArchive(this, databaseName);
	}
	
	/**
	 * The NameValueDatabaseHelper
	 * @param name
	 * @return
	 */
	protected abstract SQLiteOpenHelper getDatabaseHelper(String name);
	
	/**
	 * Pulls data from the intent to update the database.  This method is called from within a transaction.
	 * Throw a SQLException to signify a failure and rollback the transaction.
	 * @param db
	 * @param intent
	 */
	protected abstract void updateDatabase(SQLiteDatabase db, Intent intent) throws SQLException;
	
	
	private void runArchive(String databaseName) {
		SQLiteOpenHelper dbHelper = getOrCreateDatabaseHelper(databaseName);
		File dbFile = new File(dbHelper.getReadableDatabase().getPath());
		Log.i(LogUtil.TAG, "Running archive: " + dbFile.getAbsolutePath());
		dbHelper.close();
		FileArchive archive = getArchive(databaseName);
		if (archive.add(dbFile)) {
			dbFile.delete();
		}
	}
	
	private SQLiteOpenHelper getOrCreateDatabaseHelper(String databaseName) {
		if (databaseHelpers == null) {
			databaseHelpers = new HashMap<String, SQLiteOpenHelper>();
		}
		SQLiteOpenHelper dbHelper = databaseHelpers.get(databaseName);
		if (dbHelper == null) {
			Log.i(LogUtil.TAG, "DataBaseService: Creating database '" + databaseName + "'");
			dbHelper = getDatabaseHelper(databaseName);
			databaseHelpers.put(databaseName, dbHelper);
		}
		return dbHelper;
	}
	
	
	/**
	 * Write data to the database in a transaction
	 * NOTE: Should only be called by one thread at a time (the writeThread)
	 * @param databaseName
	 */
	private void writeToDatabase(String databaseName, Intent intent) {
		Log.i(LogUtil.TAG, "Writing to database");
		SQLiteOpenHelper dbHelper = getOrCreateDatabaseHelper(databaseName);
		SQLiteDatabase db = dbHelper.getWritableDatabase();
		try {
			db.beginTransaction();
			updateDatabase(db, intent);
			db.setTransactionSuccessful();
			Log.i(LogUtil.TAG, "Writing successful");
		} catch (Exception e) {
			Log.e(LogUtil.TAG, "DataBaseService: save error",e);
		} finally {
			db.endTransaction();
		}
		db.close();
	}
	
	/**
	 * Pulls data from the intent to export the data.  
	 * @param db
	 * @param intent
	 * @throws SQLException
	 */
	private void exportDatabase(String databaseName, Intent intent) throws SQLException{
		//only for reading the db
//		SQLiteOpenHelper dbHelper = new SQLiteOpenHelper(this , databaseName, null, 1) {
//
//			@Override
//			public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
//				// TODO Auto-generated method stub
//
//			}
//			@Override
//			public void onCreate(SQLiteDatabase db) {
//				// TODO Auto-generated method stub
//
//			}
//		};

    SQLiteOpenHelper dbHelper = new NameValueDatabaseHelper(this, databaseName,
        NameValueDatabaseHelper.CURRENT_VERSION);

		SQLiteDatabase db = dbHelper.getReadableDatabase();
		exportDB(db, intent);
		db.close();

	}
	
	/*
	 * Delete all the files in the /backup folder (used with caution!)
	 */
	private void clearBackup(String databaseName, Intent intent){
		// first find all backup files for this database in the /backup folder than delete them
		
		//new File(Environment.getExternalStorageDirectory(), context.getPackageName()) + "/";
		String backupRoot = FileUtil.getSdCardPath(this) + databaseName + File.separator + "backup";
//		Log.i(TAG, "backupRoot" + backupRoot);
		
		File folder = new File(backupRoot); //f should be a directory
		
		for (File file : folder.listFiles()){
			String fileName = file.getName();
			//because all backup file has the format of number-number-number_timestamp_number_Databasenmae.db
			if (fileName.endsWith(databaseName + "." + "db")){
				file.delete();
				Log.i(TAG, "Delete:" + fileName);
			}
		}

	}
	
	protected abstract void exportDB(SQLiteDatabase db, Intent intent) throws SQLException;
	

	/**
	 * Binder interface to the probe
	 */
	public class LocalBinder extends Binder {
		public DatabaseService getService() {
            return DatabaseService.this;
        }
    }
	private final IBinder mBinder = new LocalBinder();
	@Override
	public IBinder onBind(Intent intent) {
		return mBinder;
	}
}
