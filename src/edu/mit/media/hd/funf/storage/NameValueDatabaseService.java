package edu.mit.media.hd.funf.storage;

import android.content.ContentValues;
import android.content.Intent;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

public class NameValueDatabaseService extends DatabaseService {
	public static final String NAME_KEY = "NAME";
	public static final String VALUE_KEY = "VALUE";
	public static final String TIMESTAMP_KEY = "TIMESTAMP";
	
	/**
	 * The NameValueDatabaseHelper
	 * @param name
	 * @return
	 */
	protected SQLiteOpenHelper getDatabaseHelper(String name) {
		return new NameValueDatabaseHelper(this, name, NameValueDatabaseHelper.CURRENT_VERSION);
	}
	
	/**
	 * Pulls data from the intent to update the database.  This method is called from within a transaction.
	 * Throw a SQLException to signify a failure and rollback the transaction.
	 * @param db
	 * @param intent
	 */
	protected void updateDatabase(SQLiteDatabase db, Intent intent) throws SQLException {
		final long timestamp = intent.getLongExtra(TIMESTAMP_KEY, 0L);
		final String name = intent.getStringExtra(NAME_KEY);
		final String value = intent.getStringExtra(VALUE_KEY);
		if (timestamp == 0L || name == null || value == null) {
			Log.e(TAG, "Unable to save data.  Not all required values specified. " + timestamp + " " + name + " - " + value);
			throw new SQLException("Not all required fields specified.");
		}
		ContentValues cv = new ContentValues();
		cv.put(NameValueDatabaseHelper.COLUMN_NAME, name);
		cv.put(NameValueDatabaseHelper.COLUMN_VALUE, value);
		cv.put(NameValueDatabaseHelper.COLUMN_TIMESTAMP, timestamp);
		db.insertOrThrow(NameValueDatabaseHelper.DATA_TABLE.name, "", cv);
	}
}
