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

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Environment;
import android.util.Log;
import android.util.Pair;
import edu.mit.media.funf.probe.builtin.ProbeKeys;
import edu.mit.media.funf.probe.builtin.ProbeKeys.BaseProbeKeys;
import edu.mit.media.funf.probe.builtin.ProbeKeys.SensorKeys;
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
	private final String TIMESTAMP = "timestamp";
	private final String TIMEZONEOFFSET = "timezoneOffset";

	// this will keeps a record of the opened writer handle during export, and
	// needs to closed it at the end
	private Map<String, File> files;
	private Map<File, FileWriter> fileWriters;
	private Map<File, BufferedWriter> bufferedWriters;

	// public static enum ExportType{CSV, JSON, XML};
	private String exportType;
	private String exportRoot;
	
	// The below variables are used for updating the export db status
	public static final String PREFS_DBEXPORT_STAT = "db_export_status";
	public static final String STAT_EXPORT_DONE = "export_done";
	public static final String STAT_EXPORT_ING = "exporting";
	
	@SuppressLint("NewApi")
	private SharedPreferences sharedPreferences;
		 
	
	@SuppressLint("NewApi")
	@Override
	public void onCreate() {
	  super.onCreate();
	  sharedPreferences =  getSharedPreferences(PREFS_DBEXPORT_STAT, Context.MODE_PRIVATE);  
	}
	
	/**
	 * The NameValueDatabaseHelper
	 * 
	 * @param name
	 * @return
	 */
	protected SQLiteOpenHelper getDatabaseHelper(String name) {
		return new NameValueDatabaseHelper(this, name,
				NameValueDatabaseHelper.CURRENT_VERSION);
	}

	/**
	 * Pulls data from the intent to update the database. This method is called
	 * from within a transaction. Throw a SQLException to signify a failure and
	 * rollback the transaction.
	 * 
	 * @param db
	 * @param intent
	 */
	protected void updateDatabase(SQLiteDatabase db, Intent intent)
			throws SQLException {
		final long timestamp = intent.getLongExtra(TIMESTAMP_KEY, 0L);
		final String name = intent.getStringExtra(NAME_KEY);
		final String value = intent.getStringExtra(VALUE_KEY);
		if (timestamp == 0L || name == null || value == null) {
			Log.e(LogUtil.TAG,
					"Unable to save data.  Not all required values specified. "
							+ timestamp + " " + name + " - " + value);
			throw new SQLException("Not all required fields specified.");
		}
		ContentValues cv = new ContentValues();
		cv.put(NameValueDatabaseHelper.COLUMN_NAME, name);
		cv.put(NameValueDatabaseHelper.COLUMN_VALUE, value);
		cv.put(NameValueDatabaseHelper.COLUMN_TIMESTAMP, timestamp);
		db.insertOrThrow(NameValueDatabaseHelper.DATA_TABLE.name, "", cv);
	}

	private void updateSharedPref(String key, String val){
 
	  final SharedPreferences.Editor sharedPrefsEditor = sharedPreferences.edit();

	  sharedPrefsEditor.putString(key, val);
	  sharedPrefsEditor.commit();
	  
	}
	
	/**
	 * Exports data according to what the intent specify (types)
	 * 
	 */
	@SuppressLint("NewApi")
	@Override
	protected void exportDB(SQLiteDatabase db, Intent intent)
			throws SQLException {
		// check NameValueDatabaseHelper, there's only one table called 'data'
	  	updateSharedPref(PREFS_DBEXPORT_STAT, STAT_EXPORT_ING);
		String name;
		String value;
		long timestamp;

		exportType = intent.getStringExtra(EXPORT_KEY);
		files = new HashMap<String, File>();
		bufferedWriters = new HashMap<File, BufferedWriter>();
		fileWriters = new HashMap<File, FileWriter>();
		exportRoot = new File(Environment.getExternalStorageDirectory(),
				this.getPackageName())
				+ File.separator + "export";

		// clear exportRoot if there are previous export files in this folder
		File exportFolder = new File(exportRoot);
		if (exportFolder.exists()) {// if it's created all ready
			CleanAllCleaner cleaner = new CleanAllCleaner();
			cleaner.clean(exportFolder);
		}

		String sql = "select name, value, timestamp from data";
		Cursor c = db.rawQuery(sql, new String[0]);

		// For all each row in the NameValueDatabase, it can be saved to
		// different file
		// So, we are opening up multiple file writers as we iterate through
		// them

		Log.i(TAG,
				"select name, value, timestamp from data, cursor size:"
						+ c.getCount());
		if (c.moveToFirst()) {

			do {// different probename will map to different csv file
				name = c.getString(0);
				value = c.getString(1); // the main json part
				timestamp = c.getLong(2);
				writefile(name, value, timestamp);
			} while (c.moveToNext());
		}

		// closed all the opened file bufferedWriters
		for (File file : bufferedWriters.keySet()) {
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
		updateSharedPref(PREFS_DBEXPORT_STAT, STAT_EXPORT_DONE);
	}

	private File getOrCreateFile(String filename) {

		File file;
		// ex. If the 'filename' (which is the column 'name' from database
		// equals to 'wifisensor' and the type is csv
		// we will have /sdcard/packagename/export/csv/wifisensor.csv
		if (files.get(filename) == null) {
			String filePath = this.exportRoot + File.separator
					+ this.exportType + File.separator + filename + "."
					+ this.exportType;
			Log.i(TAG, "Create new file, its filePath:" + filePath);
			file = new File(filePath);
			// need to create all its parents folders
			file.getParentFile().mkdirs();
			files.put(filename, file);
			return file;
		} else {
			return files.get(filename);
		}

	}

	@SuppressLint("NewApi")
	private Pair<BufferedWriter, Boolean> getOrCreateBufferedWriter(File file) {

		BufferedWriter bw = null;
		if (bufferedWriters.get(file) == null) {
			try {
				bw = new BufferedWriter(new FileWriter(file));
				// if first time, we need to add column name in the first row
				Log.i(TAG, "Create new Buffered writer");
				bufferedWriters.put(file, bw);

			} catch (IOException e) {
				// TODO Auto-generated catch block
				Log.i(TAG, "Problem for reading the file, some other process might lock the file");
				e.printStackTrace();
			} 
			return new Pair<BufferedWriter, Boolean>(bw, new Boolean(true));

		} else {
			return new Pair<BufferedWriter, Boolean>(bufferedWriters.get(file),
					new Boolean(false));
		}

	}

	@SuppressLint("NewApi")
	private void writefile(String probename, String value, long timestamp) {
		// probename: edu.mit.media.funf.probe.builtin.BluetoothProbe
		Log.d(TAG, "value:" + value);
		// filename should be "BluetoothProbe"
		String filename = probename.substring(probename.lastIndexOf(".") + 1);
		File file = getOrCreateFile(filename);
		//
		Pair<BufferedWriter, Boolean> bwPair = getOrCreateBufferedWriter(file);
		
		//its possible that when getOrCreateBuffereredWriter(file) other process is reading/writing 
		//the file, so that bwPair's bufferedWriter is null
		if (bwPair.first == null)	{		
			return; //do nothing, this row of value will not be written to the file
		}
		BufferedWriter bw = bwPair.first;
		Boolean firstTime = bwPair.second;

		Log.i(TAG, "export type:" + exportType);
		if (this.exportType.equals(EXPORT_CSV)) {
			Log.i(TAG, "before convertCSV....");
			try{
			  	String row = convertCSV(value, firstTime.booleanValue());
				bw.append(row);
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		} else
			// just append the json data to the file
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
	
	private ArrayList<String> prepareHeader(String probeName){
		ArrayList<String> headerlist = new ArrayList<String>();
		// add Timestamp as the first column
		headerlist.add(TIMESTAMP); 
		if (probeName.equals("edu.mit.media.funf.probe.builtin.WifiProbe")){
			headerlist.add(ProbeKeys.WifiKeys.BSSID);
			headerlist.add(ProbeKeys.WifiKeys.SSID);
			headerlist.add(ProbeKeys.WifiKeys.LEVEL);
			headerlist.add(ProbeKeys.WifiKeys.CAPABILITIES);
			headerlist.add(ProbeKeys.WifiKeys.FREQUENCY);

		}
		if (probeName.equals("edu.mit.media.funf.probe.builtin.TelephonyProbe")){
		  
			headerlist.add(ProbeKeys.TelephonyKeys.DEVICE_ID);
			headerlist.add(ProbeKeys.TelephonyKeys.LINE_1_NUMBER);
			headerlist.add(ProbeKeys.TelephonyKeys.VOICEMAIL_NUMBER);
			headerlist.add(ProbeKeys.TelephonyKeys.SIM_SERIAL_NUMBER);
			headerlist.add(ProbeKeys.TelephonyKeys.SIM_OPERATOR);
			headerlist.add(ProbeKeys.TelephonyKeys.SIM_OPERATOR_NAME);
			headerlist.add(ProbeKeys.TelephonyKeys.NETWORK_COUNTRY_ISO);
			headerlist.add(ProbeKeys.TelephonyKeys.NETWORK_OPERATOR);
			headerlist.add(ProbeKeys.TelephonyKeys.NETWORK_OPERATOR_NAME);

			
		}
		if (probeName.equals("edu.mit.media.funf.probe.builtin.BluetoothProbe")){
			headerlist.add(ProbeKeys.BluetoothKeys.DEVICE);
			headerlist.add(ProbeKeys.BluetoothKeys.NAME);
			headerlist.add(ProbeKeys.BluetoothKeys.RSSI);
			headerlist.add(ProbeKeys.BluetoothKeys.CLASS);
		}
			
		if (probeName.equals("edu.mit.media.funf.probe.builtin.SmsProbe")){
			headerlist.add(ProbeKeys.SmsKeys.DATE);
			headerlist.add(ProbeKeys.SmsKeys.ADDRESS);
			headerlist.add(ProbeKeys.SmsKeys.BODY);
			headerlist.add(ProbeKeys.SmsKeys.TYPE);
			headerlist.add(ProbeKeys.SmsKeys.READ);
		
		}
		if (probeName.equals("edu.mit.media.funf.probe.builtin.ScreenProbe")){
			headerlist.add(ProbeKeys.ScreenKeys.SCREEN_ON);
			
		}
			
		if (probeName.equals("edu.mit.media.funf.probe.builtin.RunningApplicationsProbe")){
			headerlist.add(ProbeKeys.RunningApplicationsKeys.TASK_INFO);
			headerlist.add(ProbeKeys.RunningApplicationsKeys.DURATION);	
		}
		if (probeName.equals("edu.mit.media.funf.probe.builtin.ProximitySensorProbe")){
			headerlist.add(ProbeKeys.ProximitySensorKeys.DISTANCE);
			
		}
		if (probeName.equals("edu.mit.media.funf.probe.builtin.PedometerProbe")){
			headerlist.add(ProbeKeys.PedometerKeys.RAW_VALUE);
			
		}
		if (probeName.equals("edu.mit.media.funf.probe.builtin.SimpleLocationProbe")){
			headerlist.add(ProbeKeys.LocationKeys.LATITUDE);
			headerlist.add(ProbeKeys.LocationKeys.LONGITUDE);
			headerlist.add(ProbeKeys.LocationKeys.ACCURACY);
		}
			
		if (probeName.equals("edu.mit.media.funf.probe.builtin.LightSensorProbe")){
			headerlist.add(ProbeKeys.LightSensorKeys.LUX);			
		}
			
		if (probeName.equals("edu.mit.media.funf.probe.builtin.CellTowerProbe")){

			headerlist.add(ProbeKeys.CellKeys.CID);
			headerlist.add(ProbeKeys.CellKeys.LAC);

		}
		if (probeName.equals("edu.mit.media.funf.probe.builtin.CallLogProbe")){
			headerlist.add(ProbeKeys.CallLogKeys.DATE);
			headerlist.add(ProbeKeys.CallLogKeys.DURATION);
			headerlist.add(ProbeKeys.CallLogKeys.NAME);
			headerlist.add(ProbeKeys.CallLogKeys.NUMBER);
			headerlist.add(ProbeKeys.CallLogKeys.NUMBER_TYPE);
			headerlist.add(ProbeKeys.CallLogKeys.TYPE);
			
		}

		if (probeName.equals("edu.mit.media.funf.probe.builtin.BatteryProbe")){
			headerlist.add(ProbeKeys.BatteryKeys.LEVEL);		
		}
		if (probeName.equals("edu.mit.media.funf.probe.builtin.ActivityProbe")){
			headerlist.add(ProbeKeys.ActivityKeys.ACTIVITY_LEVEL);
		}

        if (probeName.equals("edu.mit.csail.dig.survey")){
            //TODO: this probe is developed in AI-sensor codebase, it's using NameValueDatabaseService,
            // but its keys are not visible to Funf's code
            headerlist.add("surveyGroup");
            headerlist.add("question");
            headerlist.add("answer");
        }
		
		// add timeOffset as the last column
		headerlist.add(TIMEZONEOFFSET);
		
		return headerlist;
	}
	
	//helper function to parse running app's taskInfo 
	@SuppressLint("NewApi")
	private String getAppName(JsonElement taskInfo){	  
//	  {"baseIntent":{
//				"mAction":"android.intent.action.MAIN",
//        		"mCategories":["android.intent.category.HOME"],
//        		"mComponent":{
//             		"mClass":"com.android.launcher2.Launcher",
//             		"mPackage":"com.android.launcher"},
//             	"mFlags":274726912},
//        "id":258,
//        "persistentId":258}	  
	  
	  Log.i(TAG, "taskInfo:" + taskInfo);
	  String packageName = "";
	  try{
		JsonElement baseIntent = taskInfo.getAsJsonObject().get("baseIntent");
		JsonElement mComponent = baseIntent.getAsJsonObject().get("mComponent");
		JsonElement mPackage = mComponent.getAsJsonObject().get("mPackage");
		Log.i(TAG, "mPackage:" + mPackage);
		packageName = mPackage.getAsJsonPrimitive().getAsString();
	  }catch(Exception e){
		packageName = "";
	  }
	  
	  final PackageManager pm = getApplicationContext().getPackageManager();
	  ApplicationInfo ai;
	  try {
	      ai = pm.getApplicationInfo(packageName, 0);
	  } catch (final NameNotFoundException e) {
	      ai = null;
	  }
	  final String applicationName = (String) (ai != null ? pm.getApplicationLabel(ai) : "(unknown)");
	  return applicationName;
	}
	
	
	// helper function to parse jsonElement of Bluetooth info; e.g. CLASS and DEVICE
	private String getBluetoothInfo(String columnName, JsonElement jsonEle){
		/*
		 * The returned JsonElements
		 * {"android.bluetooth.device.extra.CLASS":{"mClass":3670284},
		 * "android.bluetooth.device.extra.DEVICE"
		 * :{"mAddress":"00:23:39:A4:66:21"},
		 * "android.bluetooth.device.extra.NAME":"someone's mackbook",
		 * "android.bluetooth.device.extra.RSSI"
		 * :-35,"timestamp":1339360144.799}
		 */  

	  try{
		
		if (columnName.equals(ProbeKeys.BluetoothKeys.DEVICE)){
		  //{"mAddress":"00:23:39:A4:66:21"}
		  JsonElement mAddress = jsonEle.getAsJsonObject().get("mAddress");
		  Log.i(TAG, "blueAddress:" + mAddress);
		  return mAddress.getAsString();
		
		}
		if (columnName.equals(ProbeKeys.BluetoothKeys.CLASS)){
		  //{"mClass":3670284}
		  JsonElement mClass = jsonEle.getAsJsonObject().get("mClass");
		  Log.i(TAG, "mClass:" + mClass);
		  return mClass.getAsString();
		}
		
	  }catch (Exception e){
		//it's possible that there's some weird (ill-formatted) or mission field
		return "";
	  }
	  
	  return "";	  
	}
	
	//Some column's value has another jsonObject
	// this is the helper function
	private String parseComplexItem(String probeName, String columnName, JsonElement jsonEle){
	  
	  String value = "";
	  String delim = ",";
	  String space = " ";
	  Log.i(TAG, "columnName:" + columnName);
	  if (probeName.equals("edu.mit.media.funf.probe.builtin.RunningApplicationsProbe")){	
		return getAppName(jsonEle);
	  }
	  if (probeName.equals("edu.mit.media.funf.probe.builtin.BluetoothProbe")){
		return getBluetoothInfo(columnName, jsonEle);
		
	  }
		
	  else{
		// need to remove all nested comma within the object, or
		// else csv will treat it as another item
		// clean up the jsonElem
		String cleaned = jsonEle.toString().replace(delim, space);
		value = "\"" + cleaned + "\"";
	  }
	  
	  return value;
	  
	}
	
	private String convertCSV(String value, boolean firstTime){
		JsonElement jelement = new JsonParser().parse(value);
		JsonObject  jobject = jelement.getAsJsonObject();
		StringBuffer returnVal = new StringBuffer();
		
		String delim = ",";
		String probeName = jobject.getAsJsonPrimitive("probe").getAsString();
		Log.i(TAG, "probe name:" + probeName);
		ArrayList<String> headerlist = prepareHeader(probeName);
		//it's possible that this is a new probe that added from external that we don't recognize
		//then we will take all the columns

		ArrayList rowlist = new ArrayList();
		 
		jobject.remove("probe"); //remove the redundant property name
		
		for (String columnName : headerlist) {
			JsonElement obj = jobject.get(columnName);
			if (obj == null) {
				Log.i(TAG, "this has no value for field:" + columnName);
				rowlist.add("\"" + "N/A" + "\"");
			} else {
				if (obj instanceof JsonPrimitive) {
					JsonPrimitive jPrim = obj.getAsJsonPrimitive();
					//Log.i(TAG, "JPrimitive: " + jPrim.toString());
					if (columnName.equals(TIMESTAMP)){
					// when export, we only keep the epoch time to second but not to millisecond
					  Double timestamp = Math.ceil(jPrim.getAsDouble());
					  rowlist.add(timestamp.toString());
					}else {
					  rowlist.add(jPrim.toString());
					}
				} else {
				  	  rowlist.add(parseComplexItem(probeName, columnName, obj));  
				  	}
				}

			}
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
		
		//clean up both arraylists to be garbage collected
		rowlist.clear();
		headerlist.clear();

		
		return returnVal.toString();

	}
	

	private String convertBluetooth(String value, boolean firstTime) {
	  	ArrayList rowlist = new ArrayList();
	  	ArrayList<String> headerlist = new ArrayList<String>();
	  
		JsonElement jelement = new JsonParser().parse(value);
		JsonObject  jobject = jelement.getAsJsonObject();
		StringBuffer returnVal = new StringBuffer();

		StringBuffer row = new StringBuffer();
		
		String delim = ",";
		String space = " ";
		String probeName = jobject.getAsJsonPrimitive("probe").getAsString();
 
		jobject.remove("probe"); //remove the redundant property name

		// for SMS probe's header
		ArrayList<String> BluetoothHeader = new ArrayList<String>();

		BluetoothHeader.add(ProbeKeys.BluetoothKeys.RSSI);
		BluetoothHeader.add(ProbeKeys.BluetoothKeys.DEVICE);
		BluetoothHeader.add(ProbeKeys.BluetoothKeys.NAME);
		BluetoothHeader.add(ProbeKeys.BluetoothKeys.CLASS);

		headerlist = BluetoothHeader;
		headerlist.add(TIMESTAMP);
		headerlist.add(TIMEZONEOFFSET);

		
		for (String columnName : headerlist) {
			JsonElement obj = jobject.get(columnName);
			if (obj == null) {
				Log.i(TAG, "this has no value for field:" + columnName);
				rowlist.add("\"" + "N/A" + "\"");
			} else {
				if (obj instanceof JsonPrimitive) {
					JsonPrimitive jPrim = obj.getAsJsonPrimitive();
					Log.i(TAG, "JPrimitive: " + jPrim.toString());
					rowlist.add(jPrim.toString());
				} else {
					// need to remove all nested comma within the object, or
					// else csv will treat it as another item
					String cleaned = obj.toString().replace(delim, space);
					// anything that's not JsonPrimitive we will make it a
					// String
					rowlist.add("\"" + cleaned + "\"");
				}

			}
		}
 
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


	/**
	 * This is the extra handling for SMSProbe and SMSLog. Found that not all
	 * SMS and CallLogs are equal For some phones, the users might turn off the
	 * setting so that their names are not shown.
	 * 
	 * @param value
	 * @param firstTime
	 * @return
	 */
	private String convertSMSnCallLog(String value, boolean firstTime) {
	  	ArrayList rowlist = new ArrayList();
	  	ArrayList<String> headerlist = new ArrayList<String>();
		JsonElement jelement = new JsonParser().parse(value);
		JsonObject  jobject = jelement.getAsJsonObject();
		StringBuffer returnVal = new StringBuffer();

		StringBuffer row = new StringBuffer();
		
		String delim = ",";
		String space = " ";
		String probeName = jobject.getAsJsonPrimitive("probe").getAsString();
 
		jobject.remove("probe"); //remove the redundant property name

		// for SMS probe's header
		ArrayList<String> SMSHeader = new ArrayList<String>();

		SMSHeader.add(ProbeKeys.SmsKeys.ADDRESS);
		SMSHeader.add(ProbeKeys.SmsKeys.BODY);
		SMSHeader.add(ProbeKeys.SmsKeys.DATE);
		SMSHeader.add(ProbeKeys.SmsKeys.TYPE);
		SMSHeader.add(ProbeKeys.SmsKeys.READ);
		// for CallLog probe's header
		ArrayList<String> CallLogHeader = new ArrayList<String>();

		CallLogHeader.add(ProbeKeys.CallLogKeys.DATE);
		CallLogHeader.add(ProbeKeys.CallLogKeys.DURATION);
		CallLogHeader.add(ProbeKeys.CallLogKeys.NAME);
		CallLogHeader.add(ProbeKeys.CallLogKeys.NUMBER);
		CallLogHeader.add(ProbeKeys.CallLogKeys.NUMBER_TYPE);
		CallLogHeader.add(ProbeKeys.CallLogKeys.TYPE);
		
		if(probeName.equals("edu.mit.media.funf.probe.builtin.SmsProbe")){
			headerlist = SMSHeader;
		} else{
			headerlist = CallLogHeader;
		}
		
		for (String columnName : headerlist) {
			JsonElement obj = jobject.get(columnName);
			if (obj == null) {
				Log.i(TAG, "this has no value for field:" + columnName);
				rowlist.add("\"" + "N/A" + "\"");
			} else {
				if (obj instanceof JsonPrimitive) {
					JsonPrimitive jPrim = obj.getAsJsonPrimitive();
					Log.i(TAG, "JPrimitive: " + jPrim.toString());
					rowlist.add(jPrim.toString());
				} else {
					// need to remove all nested comma within the object, or
					// else csv will treat it as another item
					String cleaned = obj.toString().replace(delim, space);
					// anything that's not JsonPrimitive we will make it a
					// String
					rowlist.add("\"" + cleaned + "\"");
				}

			}
		}
 
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
