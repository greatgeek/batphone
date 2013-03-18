package org.servalproject.system;

import java.io.IOException;

import org.servalproject.ServalBatPhoneApplication;
import org.servalproject.shell.CommandLog;
import org.servalproject.shell.Shell;

import android.content.Intent;
import android.content.SharedPreferences.Editor;
import android.util.Log;

public class WifiAdhocControl {
	private final WifiControl control;
	private final ServalBatPhoneApplication app;
	private final ChipsetDetection detection;
	private static final String TAG = "AdhocControl";
	private int state = ADHOC_STATE_DISABLED;
	private WifiAdhocNetwork config;

	public static final String ADHOC_STATE_CHANGED_ACTION = "org.servalproject.ADHOC_STATE_CHANGED_ACTION";
	public static final String EXTRA_SSID = "extra_ssid";
	public static final String EXTRA_STATE = "extra_state";
	public static final String EXTRA_PREVIOUS_STATE = "extra_previous_state";

	public static final int ADHOC_STATE_DISABLED = 0;
	public static final int ADHOC_STATE_ENABLING = 1;
	public static final int ADHOC_STATE_ENABLED = 2;
	public static final int ADHOC_STATE_DISABLING = 3;
	public static final int ADHOC_STATE_ERROR = 4;

	WifiAdhocControl(WifiControl control) {
		this.control = control;
		this.app = control.app;
		this.detection = ChipsetDetection.getDetection();
	}

	public static String stateString(int state) {
		switch (state) {
		case ADHOC_STATE_DISABLED:
			return "Disabled";
		case ADHOC_STATE_ENABLING:
			return "Enabling";
		case ADHOC_STATE_ENABLED:
			return "Enabled";
		case ADHOC_STATE_DISABLING:
			return "Disabling";
		}
		return "Error";
	}

	public int getState() {
		return this.state;
	}

	public WifiAdhocNetwork getConfig() {
		return config;
	}

	private void updateState(int newState, WifiAdhocNetwork newConfig) {
		int oldState = 0;
		WifiAdhocNetwork oldConfig;

		oldState = this.state;
		oldConfig = this.config;
		this.state = newState;
		this.config = newConfig;

		if (newConfig != null)
			newConfig.setNetworkState(newState);

		if (newConfig != oldConfig && oldConfig != null)
			oldConfig.setNetworkState(ADHOC_STATE_DISABLED);

		Intent modeChanged = new Intent(ADHOC_STATE_CHANGED_ACTION);

		modeChanged.putExtra(EXTRA_SSID, config == null ? null : config.SSID);
		modeChanged.putExtra(EXTRA_STATE, newState);
		modeChanged.putExtra(EXTRA_PREVIOUS_STATE, oldState);

		app.sendStickyBroadcast(modeChanged);
	}

	private void waitForMode(Shell shell, WifiMode mode) throws IOException {
		String interfaceName = app.coretask.getProp("wifi.interface");
		WifiMode actualMode = null;

		for (int i = 0; i < 50; i++) {
			actualMode = WifiMode.getWiFiMode(shell, interfaceName);

			// We need to allow unknown for wifi drivers that lack linux
			// wireless extensions
			if (actualMode == WifiMode.Adhoc
					|| actualMode == WifiMode.Unknown)
				break;
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				Log.e("BatPhone", e.toString(), e);
			}
		}

		Log.v("BatPhone", "iwconfig;\n" + WifiMode.lastIwconfigOutput);

		if (actualMode != mode && actualMode != WifiMode.Unknown) {
			throw new IOException(
					"Failed to control Adhoc mode, mode ended up being '"
							+ actualMode + "'");
		}
	}

	synchronized void startAdhoc(Shell shell, WifiAdhocNetwork config)
			throws IOException {
		updateState(ADHOC_STATE_ENABLING, config);

		try {
			control.logStatus("Updating configuration");
			config.updateConfiguration();

			try {
				control.logStatus("Running adhoc start");
				shell.run(new CommandLog(app.coretask.DATA_FILE_PATH
						+ "/bin/adhoc start 1"));
			} catch (InterruptedException e) {
				IOException x = new IOException();
				x.initCause(e);
				throw x;
			}

			control.logStatus("Waiting for adhoc mode to start");
			waitForMode(shell, WifiMode.Adhoc);
			updateState(ADHOC_STATE_ENABLED, config);
		} catch (IOException e) {
			updateState(ADHOC_STATE_ERROR, config);
			throw e;
		}
	}

	synchronized void stopAdhoc(Shell shell) throws IOException {
		updateState(ADHOC_STATE_DISABLING, this.config);
		try {
			try {
				control.logStatus("Running adhoc stop");
				if (shell.run(new CommandLog(app.coretask.DATA_FILE_PATH
						+ "/bin/adhoc stop 1")) != 0)
					throw new IOException("Failed to stop adhoc mode");
			} catch (InterruptedException e) {
				IOException x = new IOException();
				x.initCause(e);
				throw x;
			}

			control.logStatus("Waiting for wifi to turn off");
			waitForMode(shell, WifiMode.Off);
			updateState(ADHOC_STATE_DISABLED, null);
		} catch (IOException e) {
			updateState(ADHOC_STATE_ERROR, this.config);
			throw e;
		}
	}

	private boolean testAdhoc(Chipset chipset, Shell shell) throws IOException {
		detection.setChipset(chipset);
		if (!chipset.supportedModes.contains(WifiMode.Adhoc))
			return false;

		String ssid = "TestingMesh" + Math.random();
		if (ssid.length() > 32)
			ssid = ssid.substring(0, 32);

		WifiAdhocNetwork config = WifiAdhocNetwork.getAdhocNetwork(ssid,
				"disabled", new byte[] {
						10, 0, 0, 1
			}, WifiAdhocNetwork.lengthToMask(8), 1);

		IOException exception = null;

		try {
			startAdhoc(shell, config);
		} catch (IOException e) {
			Log.e(TAG, e.getMessage(), e);
			// if starting fails, remember the exception
			exception = e;
		}

		try {
			stopAdhoc(shell);
		} catch (IOException e) {
			// if stopping fails, abort the test completely
			if (exception != null) {
				Throwable cause = e;
				while (cause.getCause() != null)
					cause = cause.getCause();
				cause.initCause(exception);
			}

			throw e;
		}

		// fail if starting failed
		return exception == null;
	}

	public boolean testAdhoc(Shell shell) {
		boolean ret = false;

		if (detection.getDetectedChipsets().size() == 0) {
			control.logStatus("Hardware is unknown, scanning for wifi modules");

			detection.inventSupport();
		}

		for (Chipset c : detection.getDetectedChipsets()) {
			control.logStatus("Testing - " + c.chipset);

			try {
				if (testAdhoc(c, shell)) {
					ret = true;
					control.logStatus("Found support for " + c.chipset);
					break;
				}
			} catch (IOException e) {
				Log.e(TAG, e.getMessage(), e);
			}
		}

		if (!ret)
			detection.setChipset(null);

		Editor ed = app.settings.edit();
		ed.putString("detectedChipset", ret ? detection.getChipset()
				: "UnKnown");
		ed.commit();

		return ret;
	}
}
