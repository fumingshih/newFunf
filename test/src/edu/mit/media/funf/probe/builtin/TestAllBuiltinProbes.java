package edu.mit.media.funf.probe.builtin;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import android.os.Debug;
import android.test.AndroidTestCase;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import edu.mit.media.funf.FunfManager;
import edu.mit.media.funf.json.IJsonObject;
import edu.mit.media.funf.probe.Probe;
import edu.mit.media.funf.probe.Probe.ContinuousProbe;
import edu.mit.media.funf.probe.Probe.DataListener;
import edu.mit.media.funf.probe.Probe.State;
import edu.mit.media.funf.probe.Probe.StateListener;


/**
 * This class turns on and off all of the builtin probes.  
 * While it doesn't test any of the output, it does ensure that basic use of the probes does not crash the process.
 * @author alangardner
 *
 */
public class TestAllBuiltinProbes extends AndroidTestCase {

	public static final String TAG = "FunfTest";
	
	private DataListener listener = new DataListener() {
		@Override
		public void onDataReceived(IJsonObject completeProbeUri, IJsonObject data) {
			Log.i(TAG, "DATA: " + completeProbeUri.toString() + " " + data.toString());
		}

		@Override
		public void onDataCompleted(IJsonObject completeProbeUri, JsonElement checkpoint) {
			Log.i(TAG, "COMPLETE: " + completeProbeUri.toString());
		}
	};
	
	
	
	private StateListener stateListener = new StateListener() {

		@Override
		public void onStateChanged(Probe probe, State previousState) {
			Log.i(TAG, probe.getClass().getName() + ": " + probe.getState());
			Log.i(TAG, getGson().toJson(probe));
		}
		
	};
	

	private Gson gson;
	public Gson getGson() {
		if (gson == null) {
			gson = new GsonBuilder().registerTypeAdapterFactory(FunfManager.getProbeFactory(getContext())).create();
		}
		return gson;
	}
	
	@SuppressWarnings("rawtypes")
	public static final Class[] ALL_PROBES = {
//		AccelerometerFeaturesProbe.class, //ok
//		AccelerometerSensorProbe.class, //ok
//		ApplicationsProbe.class, //ok
//		AudioFeaturesProbe.class, //ok
//		AudioMediaProbe.class, //ok
		BatteryProbe.class, //ok
//		BluetoothProbe.class, //configuration ok, but I have not yet get the return of scan data
//		BrowserBookmarksProbe.class, //ok
//		BrowserSearchesProbe.class, //ok
//		CallLogProbe.class, //ok, dumps all call_log
//		CellTowerProbe.class, //?, use bundle
//		ContactProbe.class, //ok
//		GravitySensorProbe.class, //ok
//		GyroscopeSensorProbe.class, //ok
//		HardwareInfoProbe.class, //ok
//		ImageMediaProbe.class, //ok
//		LightSensorProbe.class, //ok
//		LinearAccelerationSensorProbe.class,
//		LocationProbe.class, //ok, pass
//		MagneticFieldSensorProbe.class,
//		OrientationSensorProbe.class,
//		PressureSensorProbe.class,
//		ProcessStatisticsProbe.class,
//		ProximitySensorProbe.class,
//   	RotationVectorSensorProbe.class, //ok, rotation vector
//		RunningApplicationsProbe.class, //not quite sure ??? not quite sure how it works? only get the COMPLETE message
//		ServicesProbe.class,
//		SimpleLocationProbe.class, //ok
//		ScreenProbe.class, // ?? only received COMPLETE Tag
//		SmsProbe.class, //ok, return one-way hashed value of both sender and content
//		TelephonyProbe.class, //said disabled
//		TemperatureSensorProbe.class,
//		TimeOffsetProbe.class,
//		VideoMediaProbe.class,
//		WifiProbe.class
	};
	
	
	
	@SuppressWarnings("unchecked")
	public void testAll() throws ClassNotFoundException, IOException, InterruptedException {
		Log.i(TAG,"Running");
		Debug.startMethodTracing("calc");
		List<Class<? extends Probe>> allProbeClasses = Arrays.asList((Class<? extends Probe>[])ALL_PROBES);
		
		// Run one at a time
		Gson gson = getGson();
		for (Class<? extends Probe> probeClass : allProbeClasses) {
			JsonObject config = new JsonObject();
			config.addProperty("sensorDelay", SensorProbe.SENSOR_DELAY_NORMAL);
			config.addProperty("maxScanTime", "60");
			//look at SensorProbe for tag: "configurable"
			config.addProperty("asdf", 1); //it doesn't crash the 
			config.addProperty("zzzz", "__");
			Probe probe = gson.fromJson(config, probeClass);//the time that build the probe and add default configuration
			probe.addStateListener(stateListener);
			probe.registerListener(listener);
			Thread.sleep(10000L);
			if (probe instanceof ContinuousProbe) {
				((ContinuousProbe)probe).unregisterListener(listener);
			}
		}
		// Run simultaneously //why running simultaneously?
		List<Probe> probes = new ArrayList<Probe>();
		for (Class<? extends Probe> probeClass : allProbeClasses) {
			probes.add(gson.fromJson(Probe.DEFAULT_CONFIG, probeClass));
		}
		for (Probe probe : probes) {
			probe.addStateListener(stateListener);
			probe.registerListener(listener);
		}
		Thread.sleep(1000L);
		for (Probe probe : probes) {
			if (probe instanceof ContinuousProbe) {
				((ContinuousProbe)probe).unregisterListener(listener);
			}
		}
		
		Thread.sleep(1000L); // Give probes time stop

		Debug.stopMethodTracing();
	}
}
