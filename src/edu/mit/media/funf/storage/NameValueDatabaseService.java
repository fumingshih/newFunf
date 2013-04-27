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

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Environment;
import android.util.Log;
import android.util.Pair;
import edu.mit.media.funf.storage.DirectoryCleaner.CleanAllCleaner;
import edu.mit.media.funf.storage.DirectoryCleaner.KeepUnderStorageLimit;
import edu.mit.media.funf.util.FileUtil;
import edu.mit.media.funf.util.LogUtil;
import edu.mit.media.funf.util.StringUtil;


public class NameValueDatabaseService extends DatabaseService {
	public static final String NAME_KEY = "NAME";
	public static final String VALUE_KEY = "VALUE";
	public static final String TIMESTAMP_KEY = "TIMESTAMP";
	public static final String EXPORT_KEY = "EXPORT_TYPE";
	private static final String TAG = "NameValueDatabaseService";
	public static final String EXPORT_CSV = "csv";
	public static final String EXPORT_JSON = "json";
	
	//this will keeps a record of the opened writer handle during export, and needs to closed it at the end
	private Map<String, File> files;
	private Map<File, FileWriter> fileWriters;
	private Map<File, BufferedWriter> bufferedWriters;
	
//	public static enum ExportType{CSV, JSON, XML};
	private String exportType;
	private String exportRoot;
	private ArrayList headerlist = new ArrayList();
	private ArrayList rowlist = new ArrayList();
	
	
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
			Log.e(LogUtil.TAG, "Unable to save data.  Not all required values specified. " + timestamp + " " + name + " - " + value);
			throw new SQLException("Not all required fields specified.");
		}
		ContentValues cv = new ContentValues();
		cv.put(NameValueDatabaseHelper.COLUMN_NAME, name);
		cv.put(NameValueDatabaseHelper.COLUMN_VALUE, value);
		cv.put(NameValueDatabaseHelper.COLUMN_TIMESTAMP, timestamp);
		db.insertOrThrow(NameValueDatabaseHelper.DATA_TABLE.name, "", cv);
	}

	/**
	 * Exports data according to what the intent specify (types)
	 * 
	 */
	@Override
	protected void exportDB(SQLiteDatabase db, Intent intent)
			throws SQLException {
		// TODO Auto-generated method stub
		 // check NameValueDatabaseHelper, there's only one table called 'data'
		 String name;
		 String value;
		 long timestamp;

		 exportType  = intent.getStringExtra(EXPORT_KEY);
		 files = new HashMap<String, File>();
		 bufferedWriters = new HashMap<File, BufferedWriter>(); 
		 fileWriters = new HashMap<File, FileWriter>();
		 exportRoot = new File(Environment.getExternalStorageDirectory(), this.getPackageName()) + 
		 			File.separator + "export";
		 
		 //clear exportRoot if there are previous export files in this folder
		 File exportFolder = new File(exportRoot);
		 if(exportFolder.exists()){// if it's created all ready
			 CleanAllCleaner cleaner = new CleanAllCleaner();
			 cleaner.clean(exportFolder);
		 }
		 

		 String sql = "select name, value, timestamp from data";
		 Cursor c = db.rawQuery(sql, new String[0]);

		 // For all each row in the NameValueDatabase, it can be saved to different file
		 // So, we are opening up multiple file writers as we iterate through them 

		 Log.i(TAG, "select name, value, timestamp from data, cursor size:" + c.getCount());
		 if (c.moveToFirst()) {

			 do{//different probename will map to different csv file
				 name = c.getString(0);
				 value = c.getString(1); //the main json part
				 timestamp = c.getLong(2);
				 writefile(name, value, timestamp);
			 }while(c.moveToNext());
		 }
		 
		 
		 //closed all the opened file bufferedWriters
		 for (File file : bufferedWriters.keySet()){
			 BufferedWriter out = bufferedWriters.get(file);
			 try {
				out.close();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			 
		 }
		 
		 bufferedWriters.clear();
		 c.close();
	}
	
	
	private File getOrCreateFile(String filename){

		File file;
		// ex. If the 'filename' (which is the column 'name' from database equals to 'wifisensor' and the type is csv
		// we will have /sdcard/packagename/export/csv/wifisensor.csv
		if (files.get(filename) == null){
			String filePath = this.exportRoot + File.separator + this.exportType + File.separator
							  + filename + "." + this.exportType;
			Log.i(TAG, "Create new file, its filePath:" + filePath);
			file = new File(filePath);
			//need to create all its parents folders
			file.getParentFile().mkdirs();
			files.put(filename, file);
			return file;
		}else{
			return files.get(filename);
		}
		
		
	}
	private Pair<BufferedWriter, Boolean> getOrCreateBufferedWriter(File file){
		
		BufferedWriter bw = null;
		if(bufferedWriters.get(file) == null){
			try {
				bw = new BufferedWriter(new FileWriter(file));
				// if first time, we need to add column name in the first row
				Log.i(TAG, "Create new Buffered writer");
				bufferedWriters.put(file, bw);
				
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			return new Pair<BufferedWriter, Boolean>(bw, new Boolean(true));  
			
		}else{
			return new Pair<BufferedWriter, Boolean>(bufferedWriters.get(file), new Boolean(false));
		}

	}
	
	private void writefile(String probename, String value, long timestamp){
		//probename: edu.mit.media.funf.probe.builtin.BluetoothProbe
		Log.d(TAG, "value:" + value);
		String filename = probename.substring(probename.lastIndexOf(".")+1);//filename should be "BluetoothProbe"
		File file = getOrCreateFile(filename);
		// 
		Pair<BufferedWriter, Boolean> bwPair = getOrCreateBufferedWriter(file);
		
		BufferedWriter bw = bwPair.first;
		Boolean firstTime = bwPair.second;
		 
		Log.i(TAG, "export type:" + exportType);
		if(this.exportType.equals(EXPORT_CSV)){
			Log.i(TAG, "before convertCSV....");
			String row = convertCSV(value, firstTime.booleanValue());
			try {
				bw.append(row);
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}else //just append the json data to the file
			try {
				bw.append(value);
				bw.append("\n");
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			
	}
	

	private void buildXML(String value) {
		// TODO Use XmlBuilder
		;
		
	}

	private String convertCSV(String value, boolean firstTime) {
		// TODO Parse json string and write to csv row
		// {"BSSID":"28:37:37:27:35:65",
		// "SSID":"AE",
//		   "capabilities":"[WPA2-PSK-CCMP][ESS]",
//		   "frequency":2437,
//		   "level":-63,
//		   "timestamp":227337138598,
//		   "wifiSsid":{"octets":{"buf":[65,69,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0],"count":2}},
//		   "probe":"edu.mit.media.funf.probe.builtin.WifiProbe",
//		   "timezoneOffset":-1.4400000E+10}

		JsonElement jelement = new JsonParser().parse(value);
		JsonObject  jobject = jelement.getAsJsonObject();
		StringBuffer returnVal = new StringBuffer();

		StringBuffer row = new StringBuffer();
		
		String delim = ",";
		String space = " ";

		jobject.remove("probe"); //remove the redundant property name
		
		for (Map.Entry<String, JsonElement> ele: jobject.entrySet()){
		 
			JsonElement obj = ele.getValue();
			if (obj instanceof JsonPrimitive) {
				JsonPrimitive jPrim = obj.getAsJsonPrimitive();
				Log.i(TAG, "JPrimitive: " + jPrim.toString());
				rowlist.add(jPrim.toString());
				//row.append(jPrim.toString());
			} else {
				// need to remove all nested comma within the object, or else csv will treat it as another item
				String cleaned = obj.toString().replace(delim, space);
				rowlist.add("\"" + cleaned + "\""); 
				// anything that's not JsonPrimitive we will make it a String 
			}

			// if it's first time, we need to write header as well

			if (firstTime) {
				Log.i(TAG, "We are creating header: " + ele.getKey());
				headerlist.add(ele.getKey());
			}

		}// end of loop

		if (firstTime) {
			String header = StringUtil.join(headerlist, delim);
			String rowval = StringUtil.join(rowlist, delim);
			returnVal.append(header);
			returnVal.append("\n");
			returnVal.append(rowval);
			returnVal.append("\n");
			Log.d(TAG, "Header + row:" + returnVal.toString());
			 
		} else {
			String rowval = StringUtil.join(rowlist, delim);
			returnVal.append(rowval);
			returnVal.append("\n");
			Log.d(TAG, "Row only:" + returnVal.toString());
			
		}
		
		//clean up both arraylists
		rowlist.clear();
		headerlist.clear();
		
		return returnVal.toString();

	}
	
	
	
	
}
