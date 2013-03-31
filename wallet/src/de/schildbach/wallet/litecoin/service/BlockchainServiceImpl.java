/*
 * Copyright 2011-2013 the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.litecoin.service;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import android.annotation.SuppressLint;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.WifiLock;
import android.os.BatteryManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.preference.PreferenceManager;
import android.support.v4.app.NotificationCompat;
import android.text.format.DateUtils;
import android.util.Log;

import com.google.litecoin.core.AbstractPeerEventListener;
import com.google.litecoin.core.Address;
import com.google.litecoin.core.Block;
import com.google.litecoin.core.BlockChain;
import com.google.litecoin.core.Peer;
import com.google.litecoin.core.PeerEventListener;
import com.google.litecoin.core.PeerGroup;
import com.google.litecoin.core.ScriptException;
import com.google.litecoin.core.StoredBlock;
import com.google.litecoin.core.Transaction;
import com.google.litecoin.core.TransactionConfidence.ConfidenceType;
import com.google.litecoin.core.TransactionInput;
import com.google.litecoin.core.Wallet;
import com.google.litecoin.core.Wallet.BalanceType;
import com.google.litecoin.core.WalletEventListener;
import com.google.litecoin.discovery.DnsDiscovery;
import com.google.litecoin.discovery.IrcDiscovery;
import com.google.litecoin.discovery.PeerDiscovery;
import com.google.litecoin.discovery.PeerDiscoveryException;
import com.google.litecoin.store.BlockStore;
import com.google.litecoin.store.BlockStoreException;
import com.google.litecoin.store.SPVBlockStore;

import de.schildbach.wallet.litecoin.Constants;
import de.schildbach.wallet.litecoin.WalletApplication;
import de.schildbach.wallet.litecoin.WalletBalanceWidgetProvider;
import de.schildbach.wallet.litecoin.ui.WalletActivity;
import de.schildbach.wallet.litecoin.util.ThrottelingWalletChangeListener;
import de.schildbach.wallet.litecoin.util.WalletUtils;
import de.schildbach.wallet.litecoin.R;

/**
 * @author Andreas Schildbach
 */
public class BlockchainServiceImpl extends android.app.Service implements BlockchainService
{
	private WalletApplication application;
	private SharedPreferences prefs;

	private BlockStore blockStore;
	private File blockChainFile;
	private BlockChain blockChain;
	private PeerGroup peerGroup;

	private final Handler handler = new Handler();
	private final Handler delayHandler = new Handler();
	private WakeLock wakeLock;
	private WifiLock wifiLock;

	private PeerConnectivityListener peerConnectivityListener;
	private NotificationManager nm;
	private static final int NOTIFICATION_ID_CONNECTED = 0;
	private static final int NOTIFICATION_ID_COINS_RECEIVED = 1;

	private int notificationCount = 0;
	private BigInteger notificationAccumulatedAmount = BigInteger.ZERO;
	private final List<Address> notificationAddresses = new LinkedList<Address>();
	private int bestChainHeightEver;
	private boolean resetBlockchainOnShutdown = false;

	private static final int MAX_LAST_CHAIN_HEIGHTS = 10;
	private static final int IDLE_TIMEOUT_MIN = 2;

	private static final long APPWIDGET_THROTTLE_MS = DateUtils.SECOND_IN_MILLIS;

	private static final String TAG = BlockchainServiceImpl.class.getSimpleName();

	private final WalletEventListener walletEventListener = new ThrottelingWalletChangeListener(APPWIDGET_THROTTLE_MS)
	{
		@Override
		public void onThrotteledWalletChanged()
		{
			notifyWidgets();
		}

		@Override
		public void onCoinsReceived(final Wallet wallet, final Transaction tx, final BigInteger prevBalance, final BigInteger newBalance)
		{
			try
			{
				final Address from;
				if (!tx.isCoinBase())
				{
					final TransactionInput input = tx.getInputs().get(0);
					from = input.getFromAddress();
				}
				else
				{
					from = null;
				}

				final BigInteger amount = tx.getValue(wallet);
				final ConfidenceType confidenceType = tx.getConfidence().getConfidenceType();

				handler.post(new Runnable()
				{
					public void run()
					{
						final boolean isReceived = amount.signum() > 0;
						final int bestChainHeight = blockChain.getBestChainHeight();
						final boolean replaying = bestChainHeight < bestChainHeightEver;
						final boolean isReplayedTx = confidenceType == ConfidenceType.BUILDING && replaying;

						if (isReceived && !isReplayedTx)
							notifyCoinsReceived(from, amount);
					}
				});
			}
			catch (final ScriptException x)
			{
				throw new RuntimeException(x);
			}
		}
	};

	private void notifyCoinsReceived(final Address from, final BigInteger amount)
	{
		if (notificationCount == 1)
			nm.cancel(NOTIFICATION_ID_COINS_RECEIVED);

		notificationCount++;
		notificationAccumulatedAmount = notificationAccumulatedAmount.add(amount);
		if (from != null && !notificationAddresses.contains(from))
			notificationAddresses.add(from);

		final int precision = Integer.parseInt(prefs.getString(Constants.PREFS_KEY_LTC_PRECISION, Integer.toString(Constants.LTC_PRECISION)));

		final String tickerMsg = getString(R.string.notification_coins_received_msg, WalletUtils.formatValue(amount, precision))
				+ Constants.NETWORK_SUFFIX;

		final String msg = getString(R.string.notification_coins_received_msg, WalletUtils.formatValue(notificationAccumulatedAmount, precision))
				+ Constants.NETWORK_SUFFIX;

		final StringBuilder text = new StringBuilder();
		for (final Address address : notificationAddresses)
		{
			if (text.length() > 0)
				text.append(", ");
			text.append(address.toString());
		}

		if (text.length() == 0)
			text.append("unknown");

		text.insert(0, "From ");

		final NotificationCompat.Builder notification = new NotificationCompat.Builder(this);
		notification.setSmallIcon(R.drawable.stat_notify_received);
		notification.setTicker(tickerMsg);
		notification.setContentTitle(msg);
		notification.setContentText(text);
		notification.setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, WalletActivity.class), 0));
		notification.setNumber(notificationCount == 1 ? 0 : notificationCount);
		notification.setWhen(System.currentTimeMillis());
		notification.setSound(Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.coins_received));
		nm.notify(NOTIFICATION_ID_COINS_RECEIVED, notification.getNotification());
	}

	private class PeerConnectivityListener extends AbstractPeerEventListener implements OnSharedPreferenceChangeListener
	{
		private int peerCount;
		private AtomicBoolean stopped = new AtomicBoolean(false);

		public PeerConnectivityListener()
		{
			prefs.registerOnSharedPreferenceChangeListener(this);
		}

		public void stop()
		{
			stopped.set(true);

			prefs.unregisterOnSharedPreferenceChangeListener(this);

			nm.cancel(NOTIFICATION_ID_CONNECTED);
		}

		@Override
		public void onPeerConnected(final Peer peer, final int peerCount)
		{
			this.peerCount = peerCount;
			changed(peerCount);
		}

		@Override
		public void onPeerDisconnected(final Peer peer, final int peerCount)
		{
			this.peerCount = peerCount;
			changed(peerCount);
		}

		public void onSharedPreferenceChanged(final SharedPreferences sharedPreferences, final String key)
		{
			if (Constants.PREFS_KEY_CONNECTIVITY_NOTIFICATION.equals(key))
				changed(peerCount);
		}

		private void changed(final int numPeers)
		{
			if (stopped.get())
				return;

			handler.post(new Runnable()
			{
				public void run()
				{
					final boolean connectivityNotification = prefs.getBoolean(Constants.PREFS_KEY_CONNECTIVITY_NOTIFICATION, true);

					if (!connectivityNotification || numPeers == 0)
					{
						nm.cancel(NOTIFICATION_ID_CONNECTED);
					}
					else
					{
						final NotificationCompat.Builder notification = new NotificationCompat.Builder(BlockchainServiceImpl.this);
						notification.setSmallIcon(R.drawable.stat_sys_peers, numPeers > 4 ? 4 : numPeers);
						notification.setContentTitle(getString(R.string.app_name));
						notification.setContentText(getString(R.string.notification_peers_connected_msg, numPeers));
						notification.setContentIntent(PendingIntent.getActivity(BlockchainServiceImpl.this, 0, new Intent(BlockchainServiceImpl.this,
								WalletActivity.class), 0));
						notification.setWhen(System.currentTimeMillis());
						notification.setOngoing(true);
						nm.notify(NOTIFICATION_ID_CONNECTED, notification.getNotification());
					}

					// send broadcast
					sendBroadcastPeerState(numPeers);
				}
			});
		}
	}

	private final PeerEventListener blockchainDownloadListener = new AbstractPeerEventListener()
	{
		private final AtomicLong lastMessageTime = new AtomicLong(0);

		@Override
		public void onBlocksDownloaded(final Peer peer, final Block block, final int blocksLeft)
		{
			delayHandler.removeCallbacksAndMessages(null);

			final long now = System.currentTimeMillis();

			if (now - lastMessageTime.get() > Constants.BLOCKCHAIN_STATE_BROADCAST_THROTTLE_MS)
				delayHandler.post(runnable);
			else
				delayHandler.postDelayed(runnable, Constants.BLOCKCHAIN_STATE_BROADCAST_THROTTLE_MS);
		}

		private final Runnable runnable = new Runnable()
		{
			public void run()
			{
				lastMessageTime.set(System.currentTimeMillis());

				final Date bestChainDate = new Date(blockChain.getChainHead().getHeader().getTimeSeconds() * DateUtils.SECOND_IN_MILLIS);
				final int bestChainHeight = blockChain.getBestChainHeight();
				if (bestChainHeight > bestChainHeightEver)
					bestChainHeightEver = bestChainHeight;
				final boolean replaying = bestChainHeight < bestChainHeightEver;

				sendBroadcastBlockchainState(bestChainDate, bestChainHeight, replaying, ACTION_BLOCKCHAIN_STATE_DOWNLOAD_OK);
			}
		};
	};

	private final BroadcastReceiver connectivityReceiver = new BroadcastReceiver()
	{
		private boolean hasConnectivity;
		private boolean hasPower;
		private boolean hasStorage = true;

		@Override
		public void onReceive(final Context context, final Intent intent)
		{
			final String action = intent.getAction();

			if (ConnectivityManager.CONNECTIVITY_ACTION.equals(action))
			{
				hasConnectivity = !intent.getBooleanExtra(ConnectivityManager.EXTRA_NO_CONNECTIVITY, false);
				final String reason = intent.getStringExtra(ConnectivityManager.EXTRA_REASON);
				// final boolean isFailover = intent.getBooleanExtra(ConnectivityManager.EXTRA_IS_FAILOVER, false);
				Log.i(TAG, "network is " + (hasConnectivity ? "up" : "down") + (reason != null ? ": " + reason : ""));

				check();
			}
			else if (Intent.ACTION_BATTERY_CHANGED.equals(action))
			{
				final int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
				final int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
				final int plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
				hasPower = plugged != 0 || level > scale / 10;
				Log.i(TAG, "battery changed: level=" + level + "/" + scale + " plugged=" + plugged);

				check();
			}
			else if (Intent.ACTION_DEVICE_STORAGE_LOW.equals(action))
			{
				hasStorage = false;
				Log.i(TAG, "device storage low");

				check();
			}
			else if (Intent.ACTION_DEVICE_STORAGE_OK.equals(action))
			{
				hasStorage = true;
				Log.i(TAG, "device storage ok");

				check();
			}
		}

		@SuppressLint("Wakelock")
		private void check()
		{
			final Wallet wallet = application.getWallet();
			final boolean hasEverything = hasConnectivity && hasPower && hasStorage;

			if (hasEverything && peerGroup == null)
			{
				Log.d(TAG, "acquiring wakelock");
				wakeLock.acquire();

				Log.i(TAG, "starting peergroup");
				peerGroup = new PeerGroup(Constants.NETWORK_PARAMETERS, blockChain);
				peerGroup.addWallet(wallet);
				peerGroup.setUserAgent(Constants.USER_AGENT, application.applicationVersionName());
				peerGroup.addEventListener(peerConnectivityListener);

				final int maxConnectedPeers = application.maxConnectedPeers();

				final String trustedPeerHost = prefs.getString(Constants.PREFS_KEY_TRUSTED_PEER, "").trim();
				final boolean hasTrustedPeer = trustedPeerHost.length() > 0;

				final boolean connectTrustedPeerOnly = hasTrustedPeer && prefs.getBoolean(Constants.PREFS_KEY_TRUSTED_PEER_ONLY, false);
				peerGroup.setMaxConnections(connectTrustedPeerOnly ? 1 : maxConnectedPeers);

				peerGroup.addPeerDiscovery(new PeerDiscovery()
				{
					private final PeerDiscovery normalPeerDiscovery = Constants.TEST ? new IrcDiscovery(Constants.PEER_DISCOVERY_IRC_CHANNEL_TEST)
							: new DnsDiscovery(Constants.NETWORK_PARAMETERS);

					public InetSocketAddress[] getPeers(final long timeoutValue, final TimeUnit timeoutUnit) throws PeerDiscoveryException
					{
						final List<InetSocketAddress> peers = new LinkedList<InetSocketAddress>();

						boolean needsTrimPeersWorkaround = false;

						if (hasTrustedPeer)
						{
							final InetSocketAddress addr = new InetSocketAddress(trustedPeerHost, Constants.NETWORK_PARAMETERS.port);
							if (addr.getAddress() != null)
							{
								peers.add(addr);
								needsTrimPeersWorkaround = true;
							}
						}

						if (!connectTrustedPeerOnly)
							peers.addAll(Arrays.asList(normalPeerDiscovery.getPeers(timeoutValue, timeoutUnit)));

						// workaround because PeerGroup will shuffle peers
						if (needsTrimPeersWorkaround)
							while (peers.size() >= maxConnectedPeers)
								peers.remove(peers.size() - 1);

						return peers.toArray(new InetSocketAddress[0]);
					}

					public void shutdown()
					{
						normalPeerDiscovery.shutdown();
					}
				});

				// start peergroup
				peerGroup.start();
				peerGroup.startBlockChainDownload(blockchainDownloadListener);
			}
			else if (!hasEverything && peerGroup != null)
			{
				Log.i(TAG, "stopping peergroup");
				peerGroup.removeEventListener(peerConnectivityListener);
				peerGroup.removeWallet(wallet);
				peerGroup.stop();
				peerGroup = null;

				Log.d(TAG, "releasing wakelock");
				wakeLock.release();
			}

			final Date bestChainDate = new Date(blockChain.getChainHead().getHeader().getTimeSeconds() * DateUtils.SECOND_IN_MILLIS);
			final int bestChainHeight = blockChain.getBestChainHeight();
			final int download = (hasConnectivity ? 0 : ACTION_BLOCKCHAIN_STATE_DOWNLOAD_NETWORK_PROBLEM)
					| (hasPower ? 0 : ACTION_BLOCKCHAIN_STATE_DOWNLOAD_POWER_PROBLEM)
					| (hasStorage ? 0 : ACTION_BLOCKCHAIN_STATE_DOWNLOAD_STORAGE_PROBLEM);
			final boolean replaying = bestChainHeight < bestChainHeightEver;

			sendBroadcastBlockchainState(bestChainDate, bestChainHeight, replaying, download);
		}
	};

	private final BroadcastReceiver tickReceiver = new BroadcastReceiver()
	{
		private int lastChainHeight = 0;
		private final List<Integer> lastDownloadedHistory = new LinkedList<Integer>();

		@Override
		public void onReceive(final Context context, final Intent intent)
		{
			final int chainHeight = blockChain.getBestChainHeight();

			if (lastChainHeight > 0)
			{
				final int downloaded = chainHeight - lastChainHeight;

				// push number of downloaded blocks
				lastDownloadedHistory.add(0, downloaded);

				// trim
				while (lastDownloadedHistory.size() > MAX_LAST_CHAIN_HEIGHTS)
					lastDownloadedHistory.remove(lastDownloadedHistory.size() - 1);

				// print
				final StringBuilder builder = new StringBuilder();
				for (final int lastDownloaded : lastDownloadedHistory)
				{
					if (builder.length() > 0)
						builder.append(',');
					builder.append(lastDownloaded);
				}
				Log.i(TAG, "Number of blocks downloaded: " + builder);

				// determine if download is idling
				boolean isIdle = false;
				if (lastDownloadedHistory.size() >= IDLE_TIMEOUT_MIN)
				{
					isIdle = true;
					for (int i = 0; i < IDLE_TIMEOUT_MIN; i++)
					{
						if (lastDownloadedHistory.get(i) > 0)
						{
							isIdle = false;
							break;
						}
					}
				}

				// if idling, shutdown service
				if (isIdle)
				{
					Log.i(TAG, "end of block download detected, stopping service");
					stopSelf();
				}
			}

			lastChainHeight = chainHeight;
		}
	};

	public class LocalBinder extends Binder
	{
		public BlockchainService getService()
		{
			return BlockchainServiceImpl.this;
		}
	}

	private final IBinder mBinder = new LocalBinder();

	@Override
	public IBinder onBind(final Intent intent)
	{
		Log.d(TAG, ".onBind()");

		return mBinder;
	}

	@Override
	public boolean onUnbind(final Intent intent)
	{
		Log.d(TAG, ".onUnbind()");

		return super.onUnbind(intent);
	}

	@Override
	public void onCreate()
	{
		Log.d(TAG, ".onCreate()");

		super.onCreate();

		nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

		final String lockName = getPackageName() + " blockchain sync";

		final PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
		wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, lockName);

		final WifiManager wifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
		wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL, lockName);
		wifiLock.setReferenceCounted(false);

		application = (WalletApplication) getApplication();
		prefs = PreferenceManager.getDefaultSharedPreferences(this);
		final Wallet wallet = application.getWallet();

		final int versionCode = application.applicationVersionCode();
		prefs.edit().putInt(Constants.PREFS_KEY_LAST_VERSION, versionCode).commit();

		bestChainHeightEver = prefs.getInt(Constants.PREFS_KEY_BEST_CHAIN_HEIGHT_EVER, 0);

		peerConnectivityListener = new PeerConnectivityListener();

		sendBroadcastPeerState(0);

		final IntentFilter intentFilter = new IntentFilter();
		intentFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
		intentFilter.addAction(Intent.ACTION_BATTERY_CHANGED);
		intentFilter.addAction(Intent.ACTION_DEVICE_STORAGE_LOW);
		intentFilter.addAction(Intent.ACTION_DEVICE_STORAGE_OK);
		registerReceiver(connectivityReceiver, intentFilter);

		blockChainFile = new File(getDir("blockstore", Context.MODE_WORLD_READABLE | Context.MODE_WORLD_WRITEABLE), Constants.BLOCKCHAIN_FILENAME);
		final boolean blockChainFileExists = blockChainFile.exists();

		if (!blockChainFileExists)
		{
			Log.d(TAG, "blockchain does not exist, resetting wallet");

			wallet.clearTransactions(0);
			copyBlockchainSnapshot(blockChainFile);
		}

		try
		{
			blockStore = new SPVBlockStore(Constants.NETWORK_PARAMETERS, blockChainFile);
			blockStore.getChainHead(); // detect corruptions as early as possible
		}
		catch (final BlockStoreException x)
		{
			blockChainFile.delete();

			x.printStackTrace();
			throw new Error("blockstore cannot be created", x);
		}

		try
		{
			blockChain = new BlockChain(Constants.NETWORK_PARAMETERS, wallet, blockStore);
		}
		catch (final BlockStoreException x)
		{
			throw new Error("blockchain cannot be created", x);
		}

		application.getWallet().addEventListener(walletEventListener);

		registerReceiver(tickReceiver, new IntentFilter(Intent.ACTION_TIME_TICK));
	}

	@Override
	public int onStartCommand(final Intent intent, final int flags, final int startId)
	{
		if (BlockchainService.ACTION_CANCEL_COINS_RECEIVED.equals(intent.getAction()))
		{
			notificationCount = 0;
			notificationAccumulatedAmount = BigInteger.ZERO;
			notificationAddresses.clear();

			nm.cancel(NOTIFICATION_ID_COINS_RECEIVED);
		}

		if (BlockchainService.ACTION_HOLD_WIFI_LOCK.equals(intent.getAction()))
		{
			Log.d(TAG, "acquiring wifilock");
			wifiLock.acquire();
		}
		else
		{
			Log.d(TAG, "releasing wifilock");
			wifiLock.release();
		}

		if (BlockchainService.ACTION_RESET_BLOCKCHAIN.equals(intent.getAction()))
		{
			resetBlockchainOnShutdown = true;
			stopSelf();
		}

		return START_NOT_STICKY;
	}

	private void copyBlockchainSnapshot(final File file)
	{
		try
		{
			final long t = System.currentTimeMillis();

			final String blockchainSnapshotFilename = Constants.BLOCKCHAIN_SNAPSHOT_FILENAME;
			final InputStream is = getAssets().open(blockchainSnapshotFilename);
			final OutputStream os = new FileOutputStream(file);

			Log.i(TAG, "copying blockchain snapshot");
			final byte[] buf = new byte[8192];
			int read;
			while (-1 != (read = is.read(buf)))
				os.write(buf, 0, read);
			os.close();
			is.close();
			Log.i(TAG, "finished copying, took " + (System.currentTimeMillis() - t) + " ms");
		}
		catch (final IOException x)
		{
			Log.w(TAG, "failed copying, starting from genesis");
			file.delete();
		}
	}

	@Override
	public void onDestroy()
	{
		Log.d(TAG, ".onDestroy()");

		unregisterReceiver(tickReceiver);

		application.getWallet().removeEventListener(walletEventListener);

		if (peerGroup != null)
		{
			peerGroup.removeEventListener(peerConnectivityListener);
			peerGroup.removeWallet(application.getWallet());
			peerGroup.stopAndWait();

			Log.i(TAG, "peergroup stopped");
		}

		peerConnectivityListener.stop();

		unregisterReceiver(connectivityReceiver);

		removeBroadcastPeerState();
		removeBroadcastBlockchainState();

		prefs.edit().putInt(Constants.PREFS_KEY_BEST_CHAIN_HEIGHT_EVER, bestChainHeightEver).commit();

		delayHandler.removeCallbacksAndMessages(null);

		try
		{
			blockStore.close();
		}
		catch (final BlockStoreException x)
		{
			throw new RuntimeException(x);
		}

		application.saveWallet();

		if (wakeLock.isHeld())
		{
			Log.d(TAG, "wakelock still held, releasing");
			wakeLock.release();
		}

		if (wifiLock.isHeld())
		{
			Log.d(TAG, "wifilock still held, releasing");
			wifiLock.release();
		}

		if (resetBlockchainOnShutdown)
		{
			Log.d(TAG, "removing blockchain");
			blockChainFile.delete();
		}

		super.onDestroy();
	}

	@Override
	public void onLowMemory()
	{
		Log.w(TAG, "low memory detected, stopping service");
		stopSelf();
	}

	public void broadcastTransaction(final Transaction tx)
	{
		if (peerGroup != null)
			peerGroup.broadcastTransaction(tx);
	}

	public List<Peer> getConnectedPeers()
	{
		if (peerGroup != null)
			return peerGroup.getConnectedPeers();
		else
			return null;
	}

	public List<StoredBlock> getRecentBlocks(final int maxBlocks)
	{
		final List<StoredBlock> blocks = new ArrayList<StoredBlock>(maxBlocks);

		try
		{
			StoredBlock block = blockChain.getChainHead();

			while (block != null)
			{
				blocks.add(block);

				if (blocks.size() >= maxBlocks)
					break;

				block = block.getPrev(blockStore);
			}
		}
		catch (final BlockStoreException x)
		{
			// swallow
		}

		return blocks;
	}

	private void sendBroadcastPeerState(final int numPeers)
	{
		final Intent broadcast = new Intent(ACTION_PEER_STATE);
		broadcast.setPackage(getPackageName());
		broadcast.putExtra(ACTION_PEER_STATE_NUM_PEERS, numPeers);
		sendStickyBroadcast(broadcast);
	}

	private void removeBroadcastPeerState()
	{
		removeStickyBroadcast(new Intent(ACTION_PEER_STATE));
	}

	private void sendBroadcastBlockchainState(final Date chainDate, final int chainHeight, final boolean replaying, final int download)
	{
		final Intent broadcast = new Intent(ACTION_BLOCKCHAIN_STATE);
		broadcast.setPackage(getPackageName());
		broadcast.putExtra(ACTION_BLOCKCHAIN_STATE_BEST_CHAIN_DATE, chainDate);
		broadcast.putExtra(ACTION_BLOCKCHAIN_STATE_BEST_CHAIN_HEIGHT, chainHeight);
		broadcast.putExtra(ACTION_BLOCKCHAIN_STATE_REPLAYING, replaying);
		broadcast.putExtra(ACTION_BLOCKCHAIN_STATE_DOWNLOAD, download);

		sendStickyBroadcast(broadcast);
	}

	private void removeBroadcastBlockchainState()
	{
		removeStickyBroadcast(new Intent(ACTION_BLOCKCHAIN_STATE));
	}

	public void notifyWidgets()
	{
		final AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(this);

		final ComponentName providerName = new ComponentName(this, WalletBalanceWidgetProvider.class);
		final int[] appWidgetIds = appWidgetManager.getAppWidgetIds(providerName);

		if (appWidgetIds.length > 0)
		{
			final Wallet wallet = application.getWallet();
			final BigInteger balance = wallet.getBalance(BalanceType.ESTIMATED);

			WalletBalanceWidgetProvider.updateWidgets(this, appWidgetManager, appWidgetIds, balance);
		}
	}
}
