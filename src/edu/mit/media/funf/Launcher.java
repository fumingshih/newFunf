package edu.mit.media.funf;

import static edu.mit.media.funf.util.LogUtil.TAG;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class Launcher extends BroadcastReceiver {

	private static boolean launched = false;
	private static boolean keepAlive = false;
	private static boolean foreGround = false;
	private static AlarmManager alarmManager;
	
	public static void launch(Context context) {
		Log.v(TAG, "Launched!");
		Log.i(TAG, "context-info:" + context.toString());
		Intent i = new Intent(context.getApplicationContext(), FunfManager.class);
		Log.i(TAG, "Application-context:" + context.getApplicationContext().toString());
		context.getApplicationContext().startService(i);
		launched = true;
		// now we try to set the service long-running by having alarm manager keep calling it every 5 mins?
		if (!keepAlive) {
			keepAlive(context);
		}
		

	}
	
	public static void stopForeground(Context context){
		Log.i(TAG, "@Launcher, stopForeground");
		Intent i = new Intent(context.getApplicationContext(), FunfManager.class);
		i.setAction(FunfManager.ACTION_STOP_FOREGROUND);
		context.getApplicationContext().startService(i);
		
		foreGround = false;
	}
	
	public static void startForeground(Context context){
		String className = "";
		String packageName = context.getPackageName();
		Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(packageName);
		//TODO: (bug) the line above is problematic in Repl app, where it cannot get the lauchIntent?
		if (launchIntent != null) {
			className = launchIntent.getComponent().getClassName();
		}
		Log.i(TAG, "@Launcher, packageName:" + packageName);
		Log.i(TAG, "@Launcher, className:" + className);
		
		Intent i = new Intent(context.getApplicationContext(), FunfManager.class);
//		ComponentName component = new ComponentName(packageName, className);
//		i.setComponent(component);
//		i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
		i.setAction(FunfManager.ACTION_FOREGROUND);
		context.getApplicationContext().startService(i);
		foreGround = true;
	}

	private static void keepAlive(Context context) {
		// TODO Auto-generated method stub
		Log.i(TAG, "set repeating timer");
		
		Intent intent = new Intent();
		intent.setClass(context, FunfManager.class);
		intent.setAction(FunfManager.ACTION_KEEP_ALIVE);
		
		long currentTimeMillis = System.currentTimeMillis();
		long intervalMillis = 60 * 1000 * 3; // (test) if we will get keep alive from alarm manager (milliseconds)
		long startTimeMillis = intervalMillis + currentTimeMillis;
		
		PendingIntent pi = PendingIntent.getService(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);

		alarmManager = (AlarmManager) context.getApplicationContext().getSystemService(Context.ALARM_SERVICE);
		alarmManager.setRepeating(AlarmManager.RTC_WAKEUP, startTimeMillis, intervalMillis, pi);
		
		keepAlive = true;
		
	}
	
	public static boolean isForeground(){
		return foreGround;
	}

	public static boolean isLaunched() {
		return launched;
	}
	
	@Override
	public void onReceive(Context context, Intent intent) {
		launch(context);
	}

}
