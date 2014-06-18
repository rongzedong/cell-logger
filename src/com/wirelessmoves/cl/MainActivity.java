/*
 * Created by: Martin Sauter, martin.sauter@wirelessmoves.com
 *
 * Copyright (c) 2012 Martin Sauter. All Rights Reserved.
 * 
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, 
 * MA 02111-1307, USA
 */

package com.wirelessmoves.cl;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.DefaultHttpClient;

import android.app.Activity;
import android.app.AlertDialog;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.NetworkInfo.State;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.Telephony;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.TextView;
import android.content.Context;
import android.content.DialogInterface;
import android.telephony.CellIdentityGsm;
import android.telephony.CellIdentityWcdma;
import android.telephony.CellInfo;
import android.telephony.CellInfoGsm;
import android.telephony.CellSignalStrengthGsm;
import android.telephony.NeighboringCellInfo;
import android.telephony.PhoneStateListener;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;
import android.telephony.gsm.GsmCellLocation;


public class MainActivity extends Activity {
    /* menu item id's */
    private static final int RESET_COUNTER = 0;
    private static final int ABOUT = 1;
    private static final int TOGGLE_DEBUG = 2;
  
    /* names of output files */
    private String filename = "cell-log-data.txt";
    private String cellChangeFileName = "cell-change-log.kml";
  
    /* These variables need to be global, so we can used them onResume and onPause method to
       stop the listener */
    private TelephonyManager Tel;
//    private CellIdentityGsm cig;
//    private CellIdentityWcdma ciw;
    private MyPhoneStateListener MyListener;
    private boolean isListenerActive = false;
  
    /*  These variables need to be global so they can be saved when the activity exits
     *  and reloaded upon restart.
     */
    private long NumberOfSignalStrengthUpdates = 0;
  
    private long LastCellId = 0;
    private long NumberOfCellChanges = -1;
  
    private long LastLacId = 0;
    private long NumberOfLacChanges = -1;
  
    private long PreviousCells[] = new long [4];
    private int  PreviousCellsIndex = 0;
    private long NumberOfUniqueCellChanges = -1;
    
    private ArrayList<String> cidlist = new ArrayList<String>();
  
    private boolean outputDebugInfo = false;
    
    /* Buffer string to cache file operations */
    private String FileWriteBufferStr = ""; 
    
    /* a resource required to keep the phone from going to the screen saver after a timeout */
    private PowerManager.WakeLock wl;
    
    /* Variables required for GPS location information */
    private LocationManager locationManager;
    private LocationListener gpsListener; 
    private Location CurrentLocation = null;
        
    /* further GPS location variables that should be be saved to survive program restarts, e.g. after device rotation 
     *
     * Unfortunately, the Location objects for the previous location can't be saved and restored easily 
     * so it has to be handled differently.
     * For details see http://developer.android.com/guide/topics/resources/runtime-changes.html
     * 
     * Not thoroughly tested at the moment so I'm not sure the restoration of PrevLocation works. If it is not working then
     * the gps listener method initializes the previous location (which is null) to the current location so the harm of
     * this is minimized.
     * 
     */
       
    private Location PrevLocation = null;
    private double CurrentLocationLong = 0;
    private double CurrentLocationLat = 0;


    /* This method is called when the activity is first created. */
    @SuppressWarnings("deprecation")
	@Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        /* If saved variable state exists from last run, recover it */
        if (savedInstanceState != null) {
        	NumberOfSignalStrengthUpdates = savedInstanceState.getLong("NumberOfSignalStrengthUpdates");
        	
        	LastCellId = savedInstanceState.getLong("LastCellId");
        	NumberOfCellChanges = savedInstanceState.getLong("NumberOfCellChanges");
        	
        	LastLacId = savedInstanceState.getLong("LastLacId");
        	NumberOfLacChanges = savedInstanceState.getLong("NumberOfLacChanges");
        	
        	PreviousCells = savedInstanceState.getLongArray("PreviousCells");
        	PreviousCellsIndex = savedInstanceState.getInt("PreviousCellsIndex");
        	NumberOfUniqueCellChanges = savedInstanceState.getLong("NumberOfUniqueCellChanges");
        	
        	outputDebugInfo = savedInstanceState.getBoolean("outputDebugInfo");
        	
        	CurrentLocationLong = savedInstanceState.getDouble("CurrentLocationLong");
        	CurrentLocationLat = savedInstanceState.getDouble("CurrentLocationLat");
        
        	/* attempt to restore the previous gps location information object */
            PrevLocation = (Location) getLastNonConfigurationInstance();

        }
        else {
        	/* Initialize PreviousCells Array to defined values */
        	for (int x = 0; x < PreviousCells.length; x++) 
        		PreviousCells[x] = 0;
        }	
       
        /* Get a handle to the telephony manager service */
        /* A listener will be installed in the object from the onResume() method */
        MyListener = new MyPhoneStateListener();
        Tel = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
       
        /* get a handle to the power manager and set a wake lock so the screen saver
         * is not activated after a timeout */        
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wl = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK, "DoNotDimScreen");
               
        /* Get a handle to the location system for getting GPS information */
        locationManager = (LocationManager) this.getSystemService(Context.LOCATION_SERVICE);
        gpsListener = new myLocationListener();
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, gpsListener);
        
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
    	menu.add(0, RESET_COUNTER, 0, "Rest Counters");
    	menu.add(0, ABOUT, 0, "About");
    	menu.add(0, TOGGLE_DEBUG, 0, "Toggle Debug Mode");
    	//TODO add the exists menu item...
    	
		return true;
    }
    
    @Override
    public boolean onOptionsItemSelected (MenuItem item) {

    	switch (item.getItemId()) {
    	    case RESET_COUNTER:
    	    	
    	    	NumberOfCellChanges = 0;
                NumberOfLacChanges = 0;
                NumberOfSignalStrengthUpdates = 0;
                
                NumberOfUniqueCellChanges = 0;
                
            	/* Initialize PreviousCells Array to a defined value */
            	for (int x = 0; x < PreviousCells.length; x++) 
            		PreviousCells[x] = 0;
            	
       	        return true;
       	    
    	    case ABOUT:
    	    	AlertDialog.Builder builder = new AlertDialog.Builder(this);
    	    	builder.setMessage("Cell Logger\r\n2012, Martin Sauter\r\n2014, rong zedong.\r\nhttp://www.wirelessmoves.com")
    	    	       .setCancelable(false)
    	    	       .setPositiveButton("OK", new DialogInterface.OnClickListener() {
    	    	           public void onClick(DialogInterface dialog, int id) {
    	    	        	   dialog.cancel();
    	    	           }
    	    	       });
    	    	       
    	    	 AlertDialog alert = builder.create();
    	    	 alert.show();
    	    	 
    	    	 return true;

    	    case TOGGLE_DEBUG:
    	    	/* Toggle the debug behavior of the program when the user selects this menu item */
    	    	if (outputDebugInfo == false) {
    	    		outputDebugInfo = true;
    	    	}
    	    	else {
    	    		outputDebugInfo = false;
    	    	}
    	    	
    	    	return true;
    	    		
    	    default:
    	        return super.onOptionsItemSelected(item);

    	}
    }

  
    @Override
	public void onBackPressed() {
      /* do nothing to prevent the user from accidentally closing the activity this way*/
    }
    
    /* Called when the application is minimized */
    @Override
    protected void onPause()
    {
      super.onPause();
      
      /* remove the listener object from the telephony manager as otherwise several listeners
       * will appear on some Android implementations once the application is resumed. 
       */
      Tel.listen(MyListener, PhoneStateListener.LISTEN_NONE);
      isListenerActive = false;
      
      /* stop receiving GPS information */
      locationManager.removeUpdates(gpsListener);

            
      /* let the device activate the screen lock again */
      wl.release();      
    }

    /* Called when the application resumes */
    @Override
    protected void onResume()
    {
       super.onResume();
       
       if (isListenerActive == false) {
           Tel.listen(MyListener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS);
           isListenerActive = true; 
           
           /* start getting GPS information again */
           locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, gpsListener);

       }
       
       /* prevent the screen lock after a timeout again */
       wl.acquire();
    }
    
    /* Called when the activity closes or is sent to the background*/
    @Override
    public void onSaveInstanceState(Bundle savedInstanceState) {
    	    	
    	super.onSaveInstanceState(savedInstanceState);
    	      
        /* save variables */
    	savedInstanceState.putLong("NumberOfSignalStrengthUpdates", NumberOfSignalStrengthUpdates);
    	
    	savedInstanceState.putLong("LastCellId", LastCellId);
    	savedInstanceState.putLong("NumberOfCellChanges", NumberOfCellChanges);
    	
    	savedInstanceState.putLong("LastLacId", LastLacId);
    	savedInstanceState.putLong("NumberOfLacChanges", NumberOfLacChanges);
    	
    	savedInstanceState.putLongArray("PreviousCells", PreviousCells);
    	savedInstanceState.putInt("PreviousCellsIndex", PreviousCellsIndex);
    	savedInstanceState.putLong("NumberOfUniqueCellChanges", NumberOfUniqueCellChanges);
 	
        savedInstanceState.putBoolean("outputDebugInfo", outputDebugInfo);  
        
    	savedInstanceState.putDouble("CurrentLocationLong",CurrentLocationLong);
    	savedInstanceState.putDouble("CurrentLocationLat",CurrentLocationLat);

        /* save the trace data still in the write buffer into a file */
        saveDataToFile(FileWriteBufferStr, "---in save instance, " + DateFormat.getTimeInstance().format(new Date()) + "\r\n");
        FileWriteBufferStr = "";
                
    }
    /*
     * It is necessary to also store PrevLocation to survive a screen re-orientation and other things that make the app
     * restart. Unfortunately, PrevLocation is an Object to it can't be saved using savedInstanceState. For the moment,
     * only this object is saved. If several objects need to be stored a bigger object encompassing these has to be created.
     * Not needed for the moment, so only store the previous gps location object.
     * 
     * For details see: http://developer.android.com/guide/topics/resources/runtime-changes.html
     */
   
    @Override
    public Object onRetainNonConfigurationInstance() {
        return PrevLocation;
    }
   
   
    private void saveCellChangeKMLDataToFile (String LocalCellChangeKmlWriteBuffer) {
        /* write measurement data to the output file */
   	    try {
		    File root = Environment.getExternalStorageDirectory();
            if (root.canWrite()){
                File logfile = new File(root, cellChangeFileName);
                FileWriter logwriter = new FileWriter(logfile, true); /* true = append */
                BufferedWriter out = new BufferedWriter(logwriter);
                
                out.write(LocalCellChangeKmlWriteBuffer);
                out.close();
            }
        }    
        catch (IOException e) {
        /* don't do anything for the moment */
        }
    }
    
    private void saveDataToFile(String LocalFileWriteBufferStr, String id) {
        /* write measurement data to the output file */
   	    try {
		    File root = Environment.getExternalStorageDirectory();
            if (root.canWrite()){
                File logfile = new File(root, filename);
                FileWriter logwriter = new FileWriter(logfile, true); /* true = append */
                BufferedWriter out = new BufferedWriter(logwriter);
                
                /* first, save debug info if activated */
                if (outputDebugInfo == true ) out.write(id);
                
                /* now save the data buffer into the file */
                out.write(LocalFileWriteBufferStr);
                out.close();
            }
        }    
        catch (IOException e) {
        /* don't do anything for the moment */
        }
        
    }
    
    
    /* The private PhoneState listener class that overrides the signal strength change method */
    /* This is where the main activity of the this app */
    private class MyPhoneStateListener extends PhoneStateListener {
  
      private static final int MAX_FILE_BUFFER_SIZE = 2000;

	  /* Get the Signal strength from the provider each time there is an update */
      @Override
      public void onSignalStrengthsChanged(SignalStrength signalStrength) {
    	 long NewCellId = 0; 
    	 long NewLacId = 0;
    	 double DistanceToLastCell = 0;
    	 
    	 String outputText; 
    	 String outputTextCellChangeKML;
    	 
    	 /* a try enclosure is necessary as an exception is thrown inside if the network is currently
    	  * not available.
    	  */
    	 
		 outputText = "Software Version: v50\r\n";
         outputText += "DeviceId:" + String.valueOf(Tel.getDeviceId()) + "  ";
         outputText += "IMSI:" + String.valueOf(Tel.getSubscriberId()) + "\r\n";
        
         //String s = TelephonyInfo 
    	 try {
    		
    		 if (outputDebugInfo == true) outputText += "Debug Mode Activated\r\n";
    		 
    		 outputText += "\r\n";

    		 /* output signal strength value directly on canvas of the main activity */
             NumberOfSignalStrengthUpdates += 1;
             outputText += "Number of updates: " + String.valueOf(NumberOfSignalStrengthUpdates) + "\r\n\r\n";
                       
             outputText += "Network Operator: " + Tel.getNetworkOperator() + " "+ Tel.getNetworkOperatorName() + "\r\n";
             outputText += "Network Type: " + String.valueOf(Tel.getNetworkType()) + "\r\n\r\n";

             
             
             outputText += "NetworkRoaming:" +String.valueOf(Tel.isNetworkRoaming()) + "\r\n";
             
//             CellIdentityGsm cig = ;
//             CellIdentityWcdma ciw;
             
//             outputText += "CIG:" + cig.getCid() + "\r\n";
//             outputText += "CIW:" + ciw.getCid() + "\r\n";
/*
               try{
 
             List<CellInfo> cellInfos = (List<CellInfo>) Tel.getAllCellInfo();

             for(CellInfo cellInfo : cellInfos)
             {
                 CellInfoGsm cellInfoGsm = (CellInfoGsm) cellInfo;

                 CellIdentityGsm cellIdentity = cellInfoGsm.getCellIdentity();
                 CellSignalStrengthGsm cellSignalStrengthGsm = cellInfoGsm.getCellSignalStrength();

                 outputText += "cell"+ "registered: "+cellInfoGsm.isRegistered()+"\r\n";
                 outputText += "cell"+ cellIdentity.toString()+"\r\n";
                 outputText += "cell"+ cellSignalStrengthGsm.toString()+"\r\n";
             }
             }
             catch(Exception e){
                 outputText += "not support new api. \r\n";
             }
             
*/
             outputText = outputText + "Signal Strength: " + 
     		    String.valueOf(-113 + (2 * signalStrength.getGsmSignalStrength())) +  " dbm\r\n\r\n";
               
             GsmCellLocation myLocation = (GsmCellLocation) Tel.getCellLocation();
                  
             NewCellId = myLocation.getCid()  % 65536;  
             outputText += "Cell ID: " +  String.valueOf(NewCellId) + "   ";
                        
             NewLacId = myLocation.getLac();
             outputText += "LAC: " +  String.valueOf(NewLacId) + "\r\n\r\n";
                        
             /* Check if the current cell has changed and increase counter if necessary */
             if (NewCellId != LastCellId) {
            	 NumberOfCellChanges += 1; 
            	 LastCellId = NewCellId; 
             }
                          
             outputText += "Number of Cell Changes: " +  String.valueOf(NumberOfCellChanges) + "\r\n";
             
             /* Check if the current cell change is not a ping-pong cell change and increase counter */
        	 boolean IsCellInArray = false;

             for (int x = 0; x < PreviousCells.length; x++) {
            	 if (PreviousCells[x] == NewCellId){
            		 IsCellInArray = true;
            		 break;
            	 }
             }
             
             /* if the cell change was unique */
             if (IsCellInArray == false) {            	 
            	 /* increase unique cell change counter and save cell id in array at current index */
            	 NumberOfUniqueCellChanges++;
            	 PreviousCells [PreviousCellsIndex] = NewCellId;
         	         	 
            	 /* Increase index and wrap back to 0 in case it is at the end of the array */
            	 PreviousCellsIndex++;
            	 if (PreviousCellsIndex == PreviousCells.length)
            		 PreviousCellsIndex = 0;
             } /* else: do not increase the counter */
             
             outputText += "Number of Unique Cell Changes: " +  String.valueOf(NumberOfUniqueCellChanges) + "\r\n";

             
             /* Check if the current LAC has changed and increase counter if necessary */
             if (NewLacId != LastLacId) {
            	 NumberOfLacChanges += 1; 
            	 LastLacId = NewLacId; 
             }
             outputText += "Number of LAC Changes: " +  String.valueOf(NumberOfLacChanges) + "\r\n\r\n";
             
             /* Neighbor Cell Stuff */
             List<NeighboringCellInfo> nbcell = Tel.getNeighboringCellInfo();
             outputText += "Number of Neighbors: "  + String.valueOf(nbcell.size()) + "\r\n";
             Iterator<NeighboringCellInfo> it = nbcell.iterator();
             while (it.hasNext()) {
            	 outputText += String.valueOf((it.next().getCid())) + "	"; 
             }
             
             outputText += "\r\nOther signal info\r\n";
             outputText += "EcNo: " + String.valueOf(signalStrength.getCdmaEcio() +  "db\r\n");
             outputText += "WCDMA Signal: " + String.valueOf(signalStrength.getCdmaDbm() +  "dbm\r\n");
             
             outputText += "Long " + CurrentLocationLong + "\r\nLat " + CurrentLocationLat+ "\r\n";
                     
             
             /* Write the information to a file, too 
              * This information is first buffered in a string buffer and only
              * written to the file once enough data has accumulated */ 
             FileWriteBufferStr += String.valueOf(NumberOfSignalStrengthUpdates) + ", ";
             FileWriteBufferStr += DateFormat.getDateInstance().format(new Date()) + ", ";
             FileWriteBufferStr += DateFormat.getTimeInstance().format(new Date()) + ", ";
             FileWriteBufferStr += String.valueOf(Tel.getNetworkType()) + ", ";
             FileWriteBufferStr += String.valueOf(NewLacId) + ", ";
             FileWriteBufferStr += String.valueOf(NewCellId)+ ", ";
             FileWriteBufferStr += String.valueOf(-113 + (2 * signalStrength.getGsmSignalStrength()) + ", ");
             
             FileWriteBufferStr += String.valueOf(CurrentLocationLong + ", ");
             FileWriteBufferStr += String.valueOf(CurrentLocationLat + ", ");

             /* if the cell change was unique */
             if (IsCellInArray == false) {
            	/* mark it with a '1' field in the output file */
            	FileWriteBufferStr += "1, "; 
            	
            	/* calculate distance to previous cell if location information is available */
            	if (PrevLocation != null) {
            		DistanceToLastCell = PrevLocation.distanceTo(CurrentLocation);
                	FileWriteBufferStr +=  String.valueOf(DistanceToLastCell) + ", ";
                	
                	PrevLocation = CurrentLocation;               	
            	} 
            	else {
            		/* no location information is available so just put a 0 into the file */
            		FileWriteBufferStr += "0, ";
            	}
            		
             }
             else {
            	/* the cell id is not unique */
            	 
            	/* mark the non unique cell change or no cell change at all with a '0' in the output file*/
            	FileWriteBufferStr += "0, ";
            	
            	/* set the distance to the previous cell to 0 */
            	FileWriteBufferStr += "0, ";
            	
             }           	 
             
             FileWriteBufferStr += "\r\n";
             
             outputText += "File Buffer Length: " + FileWriteBufferStr.length() + "\r\n";
             
             if (FileWriteBufferStr.length() >= MAX_FILE_BUFFER_SIZE){
                 
            	 saveDataToFile(FileWriteBufferStr, "---in listener, " + DateFormat.getTimeInstance().format(new Date()) + "\r\n");
            	 FileWriteBufferStr = "";
             }
             
             /* if a cell change has occurred and we have a valid gps fix, 
              * assemble an KML placemark and save it to the output file */
             if ((IsCellInArray == false) && (PrevLocation != null)){ 

            	 if(NewCellId != -1 && String.valueOf(CurrentLocationLong) != "0.0")
            		 cidlist.add(String.valueOf(NewLacId) + "," + String.valueOf(NewCellId) +"," + String.valueOf(CurrentLocationLong) + "," + String.valueOf(CurrentLocationLat));
            	 
            	 outputTextCellChangeKML = "<Placemark>\r\n";
            	 
                 outputTextCellChangeKML += "<name>" + String.valueOf(NumberOfSignalStrengthUpdates) + "</name>\r\n"; 
                 
                 outputTextCellChangeKML += "<Snippet>This is the Snipet part</Snippet>\r\n";
                 outputTextCellChangeKML += "<description><![CDATA[<div dir=\"ltr\">"+
                 
                                           DateFormat.getDateInstance().format(new Date()) + ", " +
                                           DateFormat.getTimeInstance().format(new Date()) + "<br>\r\n" +
                                           "Network: " + Tel.getNetworkOperator() + "<br>\r\n" +
                                           "Network Type: " + String.valueOf(Tel.getNetworkType()) + "<br>\r\n" +
                                           "LAC / Cell ID: " + String.valueOf(NewLacId) + " / " + String.valueOf(NewCellId) + "<br>\r\n" +
                                           "Num. Unique Cells: " + String.valueOf(NumberOfUniqueCellChanges) + "<br>\r\n" + 
                                           "Num LACs: " + String.valueOf(NumberOfLacChanges) + "<br>\r\n" + 
                                           "Signal: " + String.valueOf(-113 + (2 * signalStrength.getGsmSignalStrength())) + " dbm<br>\r\n" +
            		                       "Distance: " + String.valueOf(DistanceToLastCell) + " m<br>" + 
                		                    "</div>]]></description>\r\n";
                 
                 /* adapt style of the placemark on the map on the kind of network used 
                  * (1 and 2 = GSM/GPRS/EDGE, 13 = LTE, all other assumed UMTS for the moment)
                  */
                 if ((Tel.getNetworkType() == 2) || (Tel.getNetworkType() == 1)) {
                     outputTextCellChangeKML += "<styleUrl>#style2</styleUrl>\r\n";
                 }
                 else if (Tel.getNetworkType() == 13){
                     outputTextCellChangeKML += "<styleUrl>#style3</styleUrl>\r\n";
                 }
                 else {
                     outputTextCellChangeKML += "<styleUrl>#style1</styleUrl>\r\n";
                 }

                 outputTextCellChangeKML += "<Point>\r\n";
                 outputTextCellChangeKML += "<coordinates>";
                 outputTextCellChangeKML += String.valueOf(CurrentLocationLong) + "," + String.valueOf(CurrentLocationLat) + ",0.0000";
                 outputTextCellChangeKML += "</coordinates>\r\n";
                 outputTextCellChangeKML += "</Point>\r\n";
                 outputTextCellChangeKML += "</Placemark>\r\n\r\n";

                 saveCellChangeKMLDataToFile(outputTextCellChangeKML);
             }
             
             //TODO try upload the data...
             
             ConnectivityManager connMgr = (ConnectivityManager) 
            	        getSystemService(Context.CONNECTIVITY_SERVICE);
    	    NetworkInfo networkInfo = connMgr.getActiveNetworkInfo();

    	    //State mobile = connMgr.getNetworkInfo(ConnectivityManager.TYPE_MOBILE).getState();
            //State wifi = connMgr.getNetworkInfo(ConnectivityManager.TYPE_WIFI).getState();
            
            outputText += "cidlist count:"+ String.valueOf(cidlist.size()) +"\r\n";

            
//     	    if (cidlist.size()>0 && networkInfo != null && networkInfo.isConnected()) {
       	    if (cidlist.size()>0 && connMgr.getNetworkInfo(ConnectivityManager.TYPE_MOBILE).getState() == State.CONNECTED) {
    	        // fetch data
    	    	String req = "";
    	    	for (int i = 0; i < cidlist.size(); i++){
    	    		req += "q[]="+ cidlist.get(i)+"&";
    	    	}
            	HttpClient httpclient = new DefaultHttpClient(); 
            	//ÄãµÄURL
            	//HttpGet
            	HttpGet httpget = new HttpGet("http://www.oo8h.com/cidsaver.php?"+req); 
          	   //outputText += "http://www.oo8h.com/cidsaver.php?"+req+ "\r\n";
            	 try { 
             	   httpclient.execute(httpget);
             	   outputText += "upload data ok("+String.valueOf(cidlist.size())+").\r\n";
            	 } catch (Exception e) { 
             	    // TODO Auto-generated catch block 
             	    e.printStackTrace(); 
                	outputText += "upload data error.\r\n";
            	 } 
     	    	cidlist.clear();
    	    } else {
    	        // display error
    	    	outputText += "No data or network connection available.\r\n";
    	    }
    	    
             // 
             /*
              	 HttpClient httpclient = new DefaultHttpClient(); 
            	 //ÄãµÄURL
            	   HttpPost httppost = new HttpPost("http://www.eoeandroid.com/post_datas.php"); 

            	   try { 
            	    List<NameValuePair> nameValuePairs = new ArrayList<NameValuePair>(2); 
            	 //Your DATA 
            	    nameValuePairs.add(new BasicNameValuePair("id", "12345")); 
            	    nameValuePairs.add(new BasicNameValuePair("stringdata", "eoeAndroid.com is Cool!")); 

            	    httppost.setEntity(new UrlEncodedFormEntity(nameValuePairs)); 

            	    HttpResponse response; 
            	    response=httpclient.execute(httppost); 
            	   } catch (ClientProtocolException e) { 
            	    // TODO Auto-generated catch block 
            	    e.printStackTrace(); 
            	   } catch (IOException e) { 
            	    // TODO Auto-generated catch block 
            	    e.printStackTrace(); 
            	   } 
            	 } 
            	  */
             
             super.onSignalStrengthsChanged(signalStrength);
             
    	 }
    	 catch (Exception e) {
    		 outputText = "No network information available..."; 
    	 }
 
         /* And finally, output the generated string with all the info retrieved to the screen */
         TextView tv = new TextView(getApplicationContext());
         tv.setText(outputText);
         setContentView(tv); 
      }

    };/* End of private Class */


    /* The private location listener class implements a location listener for GPS information */
    /* The object instantiated from this class just collects the information and stores it     */
    /* in variables for the MyPhoneStateListener in the MainAcitivty to Analyze them */
    
    private class myLocationListener implements LocationListener {
    	
      public void onLocationChanged(Location location) {
           if (location != null) {
        	   
        	   /* If the previous location was never initialized, set to the first value we get */
        	   if (PrevLocation == null){
        		   PrevLocation = location;
        	   }
        	   
        	   /* store the location in an app variable*/ 
        	   CurrentLocation = location; 
        	   
          	   /* get new location into app variables as latitude and longitude values*/
               CurrentLocationLat = location.getLatitude();
               CurrentLocationLong = location.getLongitude();
           }
      }
      
      public void onProviderDisabled(String provider) {
      }
      
      public void onProviderEnabled(String provider) {
      }
      
      public void onStatusChanged(String provider, int status, Bundle extras) {
      }
      
    }; /* end of private class */
      
      
      
}