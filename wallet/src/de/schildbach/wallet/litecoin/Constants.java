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

package de.schildbach.wallet.litecoin;

import java.io.File;
import java.math.BigInteger;

import android.os.Environment;
import android.text.format.DateUtils;

import com.google.litecoin.core.NetworkParameters;
import com.google.litecoin.core.Utils;

import de.schildbach.wallet.litecoin.R;

/**
 * @author Andreas Schildbach
 * @author Erik Gregg
 */
public class Constants
{
	public static final boolean TEST = R.class.getPackage().getName().contains("_test");

	public static final String NETWORK_SUFFIX = TEST ? " [testnet3]" : "";

	public static final NetworkParameters NETWORK_PARAMETERS = TEST ? NetworkParameters.testNet3() : NetworkParameters.prodNet();

	private static final String WALLET_FILENAME_PROD = "wallet";
	private static final String WALLET_FILENAME_TEST = "wallet-testnet";
	public static final String WALLET_FILENAME = TEST ? WALLET_FILENAME_TEST : WALLET_FILENAME_PROD;

	private static final String WALLET_FILENAME_PROTOBUF_PROD = "wallet-protobuf";
	private static final String WALLET_FILENAME_PROTOBUF_TEST = "wallet-protobuf-testnet";
	public static final String WALLET_FILENAME_PROTOBUF = TEST ? WALLET_FILENAME_PROTOBUF_TEST : WALLET_FILENAME_PROTOBUF_PROD;

	private static final String WALLET_KEY_BACKUP_BASE58_PROD = "key-backup-base58";
	private static final String WALLET_KEY_BACKUP_BASE58_TEST = "key-backup-base58-testnet";
	public static final String WALLET_KEY_BACKUP_BASE58 = TEST ? WALLET_KEY_BACKUP_BASE58_TEST : WALLET_KEY_BACKUP_BASE58_PROD;

	public static final File EXTERNAL_WALLET_BACKUP_DIR = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
	private static final String EXTERNAL_WALLET_KEY_BACKUP_PROD = "litecoin-wallet-keys";
	private static final String EXTERNAL_WALLET_KEY_BACKUP_TEST = "litecoin-wallet-keys-testnet";
	public static final String EXTERNAL_WALLET_KEY_BACKUP = TEST ? EXTERNAL_WALLET_KEY_BACKUP_TEST : EXTERNAL_WALLET_KEY_BACKUP_PROD;

	private static final String WALLET_KEY_BACKUP_SNAPSHOT_PROD = "key-backup-snapshot";
	private static final String WALLET_KEY_BACKUP_SNAPSHOT_TEST = "key-backup-snapshot-testnet";
	public static final String WALLET_KEY_BACKUP_SNAPSHOT = TEST ? WALLET_KEY_BACKUP_SNAPSHOT_TEST : WALLET_KEY_BACKUP_SNAPSHOT_PROD;

	private static final String BLOCKCHAIN_SNAPSHOT_FILENAME_PROD = "blockchain-snapshot.jpg";
	private static final String BLOCKCHAIN_SNAPSHOT_FILENAME_TEST = "blockchain-snapshot-testnet.jpg";
	public static final String BLOCKCHAIN_SNAPSHOT_FILENAME = TEST ? BLOCKCHAIN_SNAPSHOT_FILENAME_TEST : BLOCKCHAIN_SNAPSHOT_FILENAME_PROD;

	private static final String BLOCKCHAIN_FILENAME_PROD = "blockchain";
	private static final String BLOCKCHAIN_FILENAME_TEST = "blockchain-testnet";
	public static final String BLOCKCHAIN_FILENAME = TEST ? BLOCKCHAIN_FILENAME_TEST : BLOCKCHAIN_FILENAME_PROD;

	public static final String PEER_DISCOVERY_IRC_CHANNEL_PROD = "#litecoin";
	public static final String PEER_DISCOVERY_IRC_CHANNEL_TEST = "#litecoinTEST3";

	private static final String BLOCKEXPLORER_BASE_URL_PROD = "http://explorer.litecoin.net/";
	private static final String BLOCKEXPLORER_BASE_URL_TEST = "http://explorer.litecoin.net/";
	public static final String BLOCKEXPLORER_BASE_URL = TEST ? BLOCKEXPLORER_BASE_URL_TEST : BLOCKEXPLORER_BASE_URL_PROD;

	public static final String MIMETYPE_TRANSACTION = "application/x-ltctx";

	public static final int MAX_NUM_CONFIRMATIONS = 7;
	public static final String USER_AGENT = "Litecoin Wallet";
	public static final String DEFAULT_EXCHANGE_CURRENCY = "USD";
	public static final int WALLET_OPERATION_STACK_SIZE = 256 * 1024;
	public static final long BLOCKCHAIN_DOWNLOAD_THRESHOLD_MS = 5 * DateUtils.SECOND_IN_MILLIS;
	public static final long BLOCKCHAIN_STATE_BROADCAST_THROTTLE_MS = DateUtils.SECOND_IN_MILLIS;
	public static final long BLOCKCHAIN_UPTODATE_THRESHOLD_MS = DateUtils.HOUR_IN_MILLIS;

	public static final String CURRENCY_CODE_LITECOIN = "LTC";
	public static final char CHAR_HAIR_SPACE = '\u200a';
	public static final char CHAR_THIN_SPACE = '\u2009';
	public static final char CHAR_ALMOST_EQUAL_TO = '\u2248';
	public static final String CURRENCY_PLUS_SIGN = "+" + CHAR_THIN_SPACE;
	public static final String CURRENCY_MINUS_SIGN = "-" + CHAR_THIN_SPACE;
	public static final String PREFIX_ALMOST_EQUAL_TO = Character.toString(CHAR_ALMOST_EQUAL_TO) + CHAR_THIN_SPACE;
	public static final int ADDRESS_FORMAT_GROUP_SIZE = 4;
	public static final int ADDRESS_FORMAT_LINE_SIZE = 12;

	public static final int LTC_PRECISION = 8;
	public static final int LOCAL_PRECISION = 4;

	public static final String DONATION_ADDRESS = "LerikguvK4nTvhk5XUp8ofg2JgLqAGnBV3";
	public static final String REPORT_EMAIL = "ralree@gmail.com";
	public static final String REPORT_SUBJECT_ISSUE = "Reported issue";
	public static final String REPORT_SUBJECT_CRASH = "Crash report";

	public static final String LICENSE_URL = "http://www.gnu.org/licenses/gpl-3.0.txt";
	public static final String SOURCE_URL = "https://github.com/hank/litecoin-wallet";
	public static final String BINARY_URL = "https://github.com/hank/litecoin-wallet";
	public static final String CREDITS_LITECOINJ_URL = "http://code.google.com/p/litecoinj/";
	public static final String CREDITS_ZXING_URL = "http://code.google.com/p/zxing/";
	public static final String CREDITS_ICON_URL = "http://forum.litecoin.net/index.php?topic=1134.0";
	public static final String AUTHOR_TWITTER_URL = "https://twitter.com/#!/bitcoins4life";
	public static final String AUTHOR_GOOGLEPLUS_URL = "https://profiles.google.com/hardware.hank";
	public static final String MARKET_APP_URL = "market://details?id=%s";
	public static final String WEBMARKET_APP_URL = "https://play.google.com/store/apps/details?id=%s";
	public static final String MARKET_PUBLISHER_URL = "market://search?q=pub:\"Joint Security Area\"";

	private static final String VERSION_URL_PROD = "http://wallet.schildbach.de/version";
	private static final String VERSION_URL_TEST = "http://wallet.schildbach.de/version_test";
	public static final String VERSION_URL = TEST ? VERSION_URL_TEST : VERSION_URL_PROD;

	public static final String PREFS_KEY_LAST_VERSION = "last_version";
	public static final String PREFS_KEY_LAST_USED = "last_used";
	public static final String PREFS_KEY_BEST_CHAIN_HEIGHT_EVER = "best_chain_height_ever";
	public static final String PREFS_KEY_ALERT_OLD_SDK_DISMISSED = "alert_old_sdk_dismissed";
	public static final String PREFS_KEY_AUTOSYNC = "autosync";
	public static final String PREFS_KEY_CONNECTIVITY_NOTIFICATION = "connectivity_notification";
	public static final String PREFS_KEY_SELECTED_ADDRESS = "selected_address";
	public static final String PREFS_KEY_EXCHANGE_CURRENCY = "exchange_currency";
    public static final String PREFS_KEY_TRUSTED_PEER = "trusted_peer";
    public static final String PREFS_KEY_TRUSTED_PEER_PORT = "trusted_peer_port";
	public static final String PREFS_KEY_TRUSTED_PEER_ONLY = "trusted_peer_only";
	public static final String PREFS_KEY_LABS_SEND_COINS_LOW_FEE = "labs_send_coins_low_fee";
	public static final String PREFS_KEY_LABS_TRANSACTION_DETAILS = "labs_transactions_details";
	public static final String PREFS_KEY_LABS_BLOCK_EXPLORER = "labs_block_explorer";
	public static final String PREFS_KEY_LTC_PRECISION = "ltc_precision";
	public static final String PREFS_KEY_DISCLAIMER = "disclaimer";

	public static final BigInteger DEFAULT_TX_FEE = Utils.CENT.multiply(new BigInteger("2")); // 0.02

	public static final long LAST_USAGE_THRESHOLD_JUST_MS = DateUtils.HOUR_IN_MILLIS;
	public static final long LAST_USAGE_THRESHOLD_RECENTLY_MS = 2 * DateUtils.DAY_IN_MILLIS;

	public static final int SDK_JELLY_BEAN = 16;
}
