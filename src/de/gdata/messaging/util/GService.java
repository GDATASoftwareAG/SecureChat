package de.gdata.messaging.util;

import android.app.Service;
import android.content.AsyncQueryHandler;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.IBinder;
import android.provider.ContactsContract;
import android.util.Log;

import org.thoughtcrime.securesms.push.TextSecureCommunicationFactory;
import org.thoughtcrime.securesms.util.DirectoryHelper;

import java.io.IOException;

import de.gdata.messaging.TextEncrypter;
import de.gdata.messaging.isfaserverdefinitions.IRpcService;

public class GService extends Service {

  public static final int TYPE_SMS = 2;
  public static final int INCOMING = 1;
  private static Context mContext;
  private static PrivacyContentObserver privacyContentObserver;
  private static GDataPreferences preferences;
  private static IRpcService mService;

  public void init(Context context) {
    preferences = new GDataPreferences(context);
    preferences.setApplicationFont("Roboto-Light.ttf");
    AsyncQueryHandler handler =
        new AsyncQueryHandler(context.getContentResolver()) {
        };

    Uri.Builder b = new Uri.Builder();
    b.scheme(ContentResolver.SCHEME_CONTENT);
    Uri hiddenUri = Uri.parse("content://de.gdata.mobilesecurity.privacy.provider/contact/0");
    Uri hiddenNUri = Uri.parse("content://de.gdata.mobilesecurity.privacy.provider/number/0");
    Uri hiddenContactsUri = b.authority(PrivacyBridge.AUTHORITY).path("contacts/").build();
    Uri hiddenNumbersUri = b.authority(PrivacyBridge.AUTHORITY).path("numbers/").build();
    if (privacyContentObserver == null) {
      privacyContentObserver = new PrivacyContentObserver(handler);
      context.getContentResolver().
          registerContentObserver(
              ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
              true, privacyContentObserver
          );
      context.getContentResolver().
          registerContentObserver(
              hiddenUri,
              true,
              privacyContentObserver);
      context.getContentResolver().
          registerContentObserver(
              hiddenNUri,
              true,
              privacyContentObserver);
    }
    refreshPrivacyData(false);
  }
  public static void bindISFAService() {
    boolean isInstalled = true;
    try {
      if (mService == null) {
        Log.d("GDATA", "Trying to bind service " + (mService == null) + " - " + (mContext== null));
        if (mContext != null) {
          isInstalled = mContext.bindService(new Intent(GDataPreferences.INTENT_ACCESS_SERVER), mConnection, Context.BIND_AUTO_CREATE);
        }
      }
    } catch (java.lang.SecurityException e) {
      Log.e("GDATA", "Remote Service Exception:  " + "wrong signatures " + e.getMessage());
    }
    if (preferences != null) {
      if (!isInstalled) {
        preferences.setPrivacyActivated(false);
      }
    }
  }
  private static ServiceConnection mConnection = new ServiceConnection() {

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
      mService = IRpcService.Stub.asInterface(service);
      Log.d("GDATA", "Service binded " + (mService != null));
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
      mService = null;
    }
  };
  /**
   * @param sender
   * @param flag call:1 and SMS:2 flag
   * @param direction in:1 out:2
   * @return
   */
  public static boolean shallBeBlockedByFilter(String sender, int flag, int direction) {
    boolean shallBeBlocked = false;
      try {
        if (getServiceInstance() != null && getServiceInstance().shouldBeFiltered(sender, flag, direction)) {
          shallBeBlocked = true;
        }
      } catch (Exception e) {
        Log.d("GDATA", "Service error " + e.getMessage());
      }
    return shallBeBlocked;
  }

  public static IRpcService getServiceInstance() {
    if (mService == null) {
      bindISFAService();
    }
    return mService;
  }
  public static boolean shallBeBlockedByPrivacy(String sender) {
    boolean shallBeBlocked = false;
      try {
        if (getServiceInstance() != null && getServiceInstance().shouldSMSBeBlocked(sender, "")) {
          shallBeBlocked = true;
        }
      } catch (Exception e) {
        Log.d("GDATA", "Service error " + e.getMessage());
      }
    return shallBeBlocked;
  }

  public static boolean isPremiumEnabled() {
    boolean isPremiumEnabled = false;
      try {
        if (getServiceInstance() != null && getServiceInstance().hasPremiumEnabled()) {
          isPremiumEnabled = true;
          Log.d("GDATA", "MYLOG PREMIUM " +getServiceInstance().hasPremiumEnabled());
        }
      } catch (Exception e) {
        Log.d("GDATA", "Service error " + e.getMessage());
      }
    return isPremiumEnabled;
  }
  public static void executeSMSCommand(String completeMessage, String sender) {
    try {
      if (getServiceInstance() != null) {
        getServiceInstance().executeSMSComand(completeMessage, sender);
      }
    } catch (Exception e) {
      Log.d("GDATA", "Service error " + e.getMessage());
    }
  }
  public static boolean isPasswordCorrect(String pw) {
    boolean isPasswordCorrect = false;
    TextEncrypter encrypter = new TextEncrypter();
      try {
        if (getServiceInstance() != null && getServiceInstance().isPasswordCorrect(encrypter.encryptData(pw))) {
          isPasswordCorrect = true;
        }
      } catch (Exception e) {
        Log.d("GDATA", "Service error " + e.getMessage());
      }
    return isPasswordCorrect;
  }

  public static boolean isNoPasswordSet() {
    return isPasswordCorrect("");
  }

  public static String getSupressedNumbers() {
    String suppressedNumbers = "";
    if (getServiceInstance() != null) {
      try {
        suppressedNumbers = getServiceInstance().getSupressedNumbers();
      } catch (Exception e) {
        Log.d("GDATA", "Service error " + e.getMessage());
      }
    }
    return suppressedNumbers;
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }
  @Override
  public void onCreate() {
    mContext = getApplicationContext();
    PrivacyBridge.mContext = getApplicationContext();
    bindISFAService();
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    return Service.START_STICKY;
  }
  public static class AsyncTaskRefreshPrivacyData extends AsyncTask<Boolean, Void, String> {

    @Override
    protected String doInBackground(Boolean... params) {
      bindISFAService();
      PrivacyBridge.loadAllHiddenContacts(mContext);
      try {
        DirectoryHelper.refreshDirectory(mContext, TextSecureCommunicationFactory.createManager(mContext));
      } catch (IOException e) {
        Log.d("GDATA", "Couldn`t load SecureChat contacts");
      }
      return null;
    }

    @Override
    protected void onProgressUpdate(Void... values) {
    }
  }

  public static void refreshPrivacyData(boolean fullReload) {
    //nothing big happens here anynmore, so that it isnt neccessary to check whether its already running or not
    new AsyncTaskRefreshPrivacyData().execute(fullReload);
  }
}