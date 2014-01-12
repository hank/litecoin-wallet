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

package de.schildbach.wallet.litecoin.ui;

import java.math.BigDecimal;
import java.math.BigInteger;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Process;
import android.preference.PreferenceManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.MeasureSpec;
import android.view.View.OnClickListener;
import android.view.View.OnFocusChangeListener;
import android.view.ViewGroup;
import android.widget.*;

import com.actionbarsherlock.app.SherlockFragment;
import com.actionbarsherlock.view.ActionMode;
import com.actionbarsherlock.view.Menu;
import com.actionbarsherlock.view.MenuInflater;
import com.actionbarsherlock.view.MenuItem;
import com.google.litecoin.core.*;
import com.google.litecoin.core.TransactionConfidence.ConfidenceType;
import com.google.litecoin.core.Wallet.BalanceType;
import com.google.litecoin.core.Wallet.SendRequest;
import com.google.litecoin.uri.LitecoinURI;
import com.google.litecoin.uri.LitecoinURIParseException;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentIntegratorSupportV4;
import com.google.zxing.integration.android.IntentResult;
import de.schildbach.wallet.litecoin.AddressBookProvider;
import de.schildbach.wallet.litecoin.Constants;
import de.schildbach.wallet.litecoin.WalletApplication;
import de.schildbach.wallet.litecoin.integration.android.LitecoinIntegration;
import de.schildbach.wallet.litecoin.service.BlockchainService;
import de.schildbach.wallet.litecoin.service.BlockchainServiceImpl;
import de.schildbach.wallet.litecoin.util.WalletUtils;
import de.schildbach.wallet.litecoin.R;

/**
 * @author Andreas Schildbach
 */
public final class SendCoinsFragment extends SherlockFragment implements AmountCalculatorFragment.Listener
{
	private AbstractWalletActivity activity;
	private WalletApplication application;
	private ContentResolver contentResolver;
	private SharedPreferences prefs;
	private Wallet wallet;

	private BlockchainService service;
	private final Handler handler = new Handler();
	private HandlerThread backgroundThread;
	private Handler backgroundHandler;

	private AutoCompleteTextView receivingAddressView;
	private View receivingStaticView;
	private TextView receivingStaticAddressView;
	private TextView receivingStaticLabelView;
	private CurrencyAmountView amountView;
	private CurrencyAmountView feeView;

	private ListView sentTransactionView;
	private TransactionsListAdapter sentTransactionListAdapter;
	private Button viewGo;
	private Button viewCancel;

	private TextView popupMessageView;
	private View popupAvailableView;
	private PopupWindow popupWindow;

	private MenuItem scanAction;

	private Address validatedAddress = null;
	private String receivingLabel = null;
	private boolean isValidAmounts = false;
	private State state = State.INPUT;
	private Transaction sentTransaction = null;

	private static final int REQUEST_CODE_SCAN = 0;

	private enum State
	{
		INPUT, PREPARATION, SENDING, SENT, FAILED
	}

	private final ServiceConnection serviceConnection = new ServiceConnection()
	{
		public void onServiceConnected(final ComponentName name, final IBinder binder)
		{
			service = ((BlockchainServiceImpl.LocalBinder) binder).getService();
		}

		public void onServiceDisconnected(final ComponentName name)
		{
			service = null;
		}
	};

	private final class ReceivingAddressListener implements OnFocusChangeListener, TextWatcher
	{
		public void onFocusChange(final View v, final boolean hasFocus)
		{
			if (!hasFocus)
				validateReceivingAddress(true);
		}

		public void afterTextChanged(final Editable s)
		{
			dismissPopup();

			receivingLabel = null;

			validateReceivingAddress(false);
		}

		public void beforeTextChanged(final CharSequence s, final int start, final int count, final int after)
		{
		}

		public void onTextChanged(final CharSequence s, final int start, final int before, final int count)
		{
		}
	}

	private final ReceivingAddressListener receivingAddressListener = new ReceivingAddressListener();

	private final CurrencyAmountView.Listener amountsListener = new CurrencyAmountView.Listener()
	{
		public void changed()
		{
			dismissPopup();
            Log.d("Litecoin", "Amount: " + amountView.getAmount() + ", Fee: " + feeView.getAmount());
			validateAmounts(false);
		}

		public void done()
		{
			validateAmounts(true);

			viewGo.requestFocusFromTouch();
		}

		public void focusChanged(final boolean hasFocus)
		{
			if (!hasFocus)
			{
				validateAmounts(true);
			}
		}
	};

	private final ContentObserver contentObserver = new ContentObserver(handler)
	{
		@Override
		public void onChange(final boolean selfChange)
		{
			updateView();
		}
	};

	private final TransactionConfidence.Listener sentTransactionConfidenceListener = new TransactionConfidence.Listener()
	{
		public void onConfidenceChanged(final Transaction tx)
		{
			activity.runOnUiThread(new Runnable()
			{
				public void run()
				{
					sentTransactionListAdapter.notifyDataSetChanged();

					if (state == State.SENDING)
					{
						final TransactionConfidence confidence = sentTransaction.getConfidence();

						if (confidence.getConfidenceType() == ConfidenceType.DEAD)
							state = State.FAILED;
						else if (confidence.numBroadcastPeers() > 1 || confidence.getConfidenceType() == ConfidenceType.BUILDING)
							state = State.SENT;

						updateView();
					}
				}
			});
		}
	};

	@Override
	public void onAttach(final Activity activity)
	{
		super.onAttach(activity);

		this.activity = (AbstractWalletActivity) activity;
		application = (WalletApplication) activity.getApplication();
		contentResolver = activity.getContentResolver();
		prefs = PreferenceManager.getDefaultSharedPreferences(activity);
		wallet = application.getWallet();
	}

	@Override
	public void onCreate(final Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);

		setHasOptionsMenu(true);

		if (savedInstanceState != null)
		{
			state = (State) savedInstanceState.getSerializable("state");

			if (savedInstanceState.containsKey("validated_address_bytes"))
				validatedAddress = new Address((NetworkParameters) savedInstanceState.getSerializable("validated_address_params"),
						savedInstanceState.getByteArray("validated_address_bytes"));
			else
				validatedAddress = null;

			receivingLabel = savedInstanceState.getString("receiving_label");

			isValidAmounts = savedInstanceState.getBoolean("is_valid_amounts");

			if (savedInstanceState.containsKey("sent_transaction_hash"))
			{
				sentTransaction = wallet.getTransaction((Sha256Hash) savedInstanceState.getSerializable("sent_transaction_hash"));
				sentTransaction.getConfidence().addEventListener(sentTransactionConfidenceListener);
			}
		}

		activity.bindService(new Intent(activity, BlockchainServiceImpl.class), serviceConnection, Context.BIND_AUTO_CREATE);

		backgroundThread = new HandlerThread("backgroundThread", Process.THREAD_PRIORITY_BACKGROUND);
		backgroundThread.start();
		backgroundHandler = new Handler(backgroundThread.getLooper());
	}

	@Override
	public View onCreateView(final LayoutInflater inflater, final ViewGroup container, final Bundle savedInstanceState)
	{
		final View view = inflater.inflate(R.layout.send_coins_fragment, container);

		receivingAddressView = (AutoCompleteTextView) view.findViewById(R.id.send_coins_receiving_address);
		receivingAddressView.setAdapter(new AutoCompleteAddressAdapter(activity, null));
		receivingAddressView.setOnFocusChangeListener(receivingAddressListener);
		receivingAddressView.addTextChangedListener(receivingAddressListener);

		receivingStaticView = view.findViewById(R.id.send_coins_receiving_static);
		receivingStaticAddressView = (TextView) view.findViewById(R.id.send_coins_receiving_static_address);
		receivingStaticLabelView = (TextView) view.findViewById(R.id.send_coins_receiving_static_label);

		receivingStaticView.setOnFocusChangeListener(new OnFocusChangeListener()
		{
			private ActionMode actionMode;

			public void onFocusChange(final View v, final boolean hasFocus)
			{
				if (hasFocus)
				{
					actionMode = activity.startActionMode(new ActionMode.Callback()
					{
						public boolean onCreateActionMode(final ActionMode mode, final Menu menu)
						{
							final MenuInflater inflater = mode.getMenuInflater();
							inflater.inflate(R.menu.send_coins_address_context, menu);

							return true;
						}

						public boolean onPrepareActionMode(final ActionMode mode, final Menu menu)
						{
							return false;
						}

						public boolean onActionItemClicked(final ActionMode mode, final MenuItem item)
						{
							switch (item.getItemId())
							{
								case R.id.send_coins_address_context_edit_address:
									handleEditAddress();

									mode.finish();
									return true;

								case R.id.send_coins_address_context_clear:
									handleClear();

									mode.finish();
									return true;
							}

							return false;
						}

						public void onDestroyActionMode(final ActionMode mode)
						{
							if (receivingStaticView.hasFocus())
								amountView.requestFocus();
						}

						private void handleEditAddress()
						{
							EditAddressBookEntryFragment.edit(getFragmentManager(), validatedAddress.toString());
						}

						private void handleClear()
						{
							// switch from static to input
							validatedAddress = null;
							receivingLabel = null;
							receivingAddressView.setText(null);
							receivingStaticAddressView.setText(null);

							updateView();

							receivingAddressView.requestFocus();
						}
					});
				}
				else
				{
					actionMode.finish();
				}
			}
		});

		amountView = (CurrencyAmountView) view.findViewById(R.id.send_coins_amount);
		amountView.setContextButton(R.drawable.ic_input_calculator, new OnClickListener()
		{
			public void onClick(final View v)
			{
				AmountCalculatorFragment.calculate(getFragmentManager(), SendCoinsFragment.this);
			}
		});

		feeView = (CurrencyAmountView) view.findViewById(R.id.send_coins_fee);
		feeView.setAmount(Constants.DEFAULT_TX_FEE);

		sentTransactionView = (ListView) view.findViewById(R.id.send_coins_sent_transaction);
		sentTransactionListAdapter = new TransactionsListAdapter(activity, wallet, application.maxConnectedPeers());
		sentTransactionView.setAdapter(sentTransactionListAdapter);

		viewGo = (Button) view.findViewById(R.id.send_coins_go);
		viewGo.setOnClickListener(new OnClickListener()
		{
			public void onClick(final View v)
			{
				validateReceivingAddress(true);
				validateAmounts(true);

				if (everythingValid())
				{
					final BigInteger fee = feeView.getAmount();

					if (fee.compareTo(Constants.DEFAULT_TX_FEE) >= 0)
						handleGo();
					else
						feeDialog();
				}
			}

			private void feeDialog()
			{
				final boolean allowLowFee = prefs.getBoolean(Constants.PREFS_KEY_LABS_SEND_COINS_LOW_FEE, false);

				final AlertDialog.Builder dialog = new AlertDialog.Builder(activity);
				dialog.setMessage(getString(R.string.send_coins_dialog_fee_message,
						Constants.CURRENCY_CODE_LITECOIN + " " + WalletUtils.formatValue(Constants.DEFAULT_TX_FEE, Constants.LTC_PRECISION)));
				if (allowLowFee)
				{
					dialog.setPositiveButton(R.string.send_coins_dialog_fee_button_send, new DialogInterface.OnClickListener()
					{
						public void onClick(final DialogInterface dialog, final int which)
						{
							handleGo();
						}
					});
				}
				else
				{
					dialog.setPositiveButton(R.string.send_coins_dialog_fee_button_adjust, new DialogInterface.OnClickListener()
					{
						public void onClick(final DialogInterface dialog, final int which)
						{
							feeView.setAmount(Constants.DEFAULT_TX_FEE);
						}
					});
				}
				dialog.setNegativeButton(R.string.send_coins_dialog_fee_button_dismiss, null);
				dialog.show();
			}
		});

		viewCancel = (Button) view.findViewById(R.id.send_coins_cancel);
		viewCancel.setOnClickListener(new OnClickListener()
		{
			public void onClick(final View v)
			{
				if (state == State.INPUT)
					activity.setResult(Activity.RESULT_CANCELED);

				activity.finish();
			}
		});

		popupMessageView = (TextView) inflater.inflate(R.layout.send_coins_popup_message, container);

		popupAvailableView = inflater.inflate(R.layout.send_coins_popup_available, container);

		return view;
	}

	@Override
	public void onResume()
	{
		super.onResume();

		contentResolver.registerContentObserver(AddressBookProvider.contentUri(activity.getPackageName()), true, contentObserver);

		amountView.setListener(amountsListener);

		feeView.setListener(amountsListener);

		updateView();
	}

	@Override
	public void onPause()
	{
		feeView.setListener(null);

		amountView.setListener(null);

		contentResolver.unregisterContentObserver(contentObserver);

		super.onPause();
	}

	@Override
	public void onSaveInstanceState(final Bundle outState)
	{
		super.onSaveInstanceState(outState);

		outState.putSerializable("state", state);
		if (validatedAddress != null)
		{
			outState.putSerializable("validated_address_params", validatedAddress.getParameters());
			outState.putByteArray("validated_address_bytes", validatedAddress.getHash160());
			outState.putBoolean("is_valid_amounts", isValidAmounts);
		}
		outState.putString("receiving_label", receivingLabel);

		if (sentTransaction != null)
			outState.putSerializable("sent_transaction_hash", sentTransaction.getHash());
	}

	@Override
	public void onDestroy()
	{
		backgroundThread.getLooper().quit();

		activity.unbindService(serviceConnection);

		if (sentTransaction != null)
			sentTransaction.getConfidence().removeEventListener(sentTransactionConfidenceListener);

		super.onDestroy();
	}

	@SuppressLint("InlinedApi")
	@Override
	public void onCreateOptionsMenu(final Menu menu, final MenuInflater inflater)
	{
		inflater.inflate(R.menu.send_coins_fragment_options, menu);

		scanAction = menu.findItem(R.id.send_coins_options_scan);

		final PackageManager pm = activity.getPackageManager();
		scanAction.setVisible(pm.hasSystemFeature(PackageManager.FEATURE_CAMERA) || pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT));

		super.onCreateOptionsMenu(menu, inflater);
	}

	@Override
	public boolean onOptionsItemSelected(final MenuItem item)
	{
		switch (item.getItemId())
		{
			case R.id.send_coins_options_scan:
				handleScan();
				return true;
		}

		return super.onOptionsItemSelected(item);
	}

	private void validateReceivingAddress(final boolean popups)
	{
		try
		{
			final String addressStr = receivingAddressView.getText().toString().trim();
			if (addressStr.length() > 0)
			{
				final NetworkParameters addressParams = Address.getParametersFromAddress(addressStr);
				if (addressParams != null && !addressParams.equals(Constants.NETWORK_PARAMETERS))
				{
					// address is valid, but from different known network
					if (popups)
						popupMessage(receivingAddressView,
								getString(R.string.send_coins_fragment_receiving_address_error_cross_network, addressParams.getId()));
				}
				else if (addressParams == null)
				{
					// address is valid, but from different unknown network
					if (popups)
						popupMessage(receivingAddressView, getString(R.string.send_coins_fragment_receiving_address_error_cross_network_unknown));
				}
				else
				{
					// valid address
					validatedAddress = new Address(Constants.NETWORK_PARAMETERS, addressStr);
				}
			}
			else
			{
				// empty field should not raise error message
			}
		}
		catch (final AddressFormatException x)
		{
			// could not decode address at all
			if (popups)
				popupMessage(receivingAddressView, getString(R.string.send_coins_fragment_receiving_address_error));
		}

		updateView();
	}

	private void validateAmounts(final boolean popups)
	{
		isValidAmounts = false;

		final BigInteger amount = amountView.getAmount();

		if (amount == null)
		{
			// empty amount
			if (popups)
				popupMessage(amountView, getString(R.string.send_coins_fragment_amount_empty));
		}
		else if (amount.signum() > 0)
		{
			final BigInteger estimated = wallet.getBalance(BalanceType.ESTIMATED);
			final BigInteger available = wallet.getBalance(BalanceType.AVAILABLE);
			final BigInteger pending = estimated.subtract(available);
			// TODO subscribe to wallet changes

			final BigInteger availableAfterAmount = available.subtract(amount);
			final boolean enoughFundsForAmount = availableAfterAmount.signum() >= 0;

			if (enoughFundsForAmount)
			{
				final BigInteger fee = feeView.getAmount();
				final boolean validFee = fee != null && fee.signum() >= 0;

				if (validFee)
				{
					final boolean enoughFunds = availableAfterAmount.subtract(fee).signum() >= 0;

					if (enoughFunds)
					{
						// everything fine
						isValidAmounts = true;
					}
					else
					{
						// not enough funds for fee
						if (popups)
							popupAvailable(feeView, availableAfterAmount, pending);
					}
				}
				else
				{
					// invalid fee
					if (popups)
						popupMessage(feeView, getString(R.string.send_coins_fragment_amount_error));
				}
			}
			else
			{
				// not enough funds for amount
				if (popups)
					popupAvailable(amountView, available, pending);
			}
		}
		else
		{
			// invalid amount
			if (popups)
				popupMessage(amountView, getString(R.string.send_coins_fragment_amount_error));
		}

		updateView();
	}

	private void popupMessage(final View anchor, final String message)
	{
		dismissPopup();

		popupMessageView.setText(message);
		popupMessageView.setMaxWidth(getView().getWidth());

		popup(anchor, popupMessageView);
	}

	private void popupAvailable(final View anchor, final BigInteger available, final BigInteger pending)
	{
		dismissPopup();

		final CurrencyTextView viewAvailable = (CurrencyTextView) popupAvailableView.findViewById(R.id.send_coins_popup_available_amount);
		viewAvailable.setPrefix(Constants.CURRENCY_CODE_LITECOIN);
		viewAvailable.setAmount(available);

		final TextView viewPending = (TextView) popupAvailableView.findViewById(R.id.send_coins_popup_available_pending);
		viewPending.setVisibility(pending.signum() > 0 ? View.VISIBLE : View.GONE);
		viewPending.setText(getString(R.string.send_coins_fragment_pending, WalletUtils.formatValue(pending, Constants.LTC_PRECISION)));

		popup(anchor, popupAvailableView);
	}

	private void popup(final View anchor, final View contentView)
	{
		contentView.measure(MeasureSpec.makeMeasureSpec(MeasureSpec.UNSPECIFIED, 0), MeasureSpec.makeMeasureSpec(MeasureSpec.UNSPECIFIED, 0));

		popupWindow = new PopupWindow(contentView, contentView.getMeasuredWidth(), contentView.getMeasuredHeight(), false);
		popupWindow.showAsDropDown(anchor);

		// hack
		contentView.setBackgroundResource(popupWindow.isAboveAnchor() ? R.drawable.popup_frame_above : R.drawable.popup_frame_below);
	}

	private void dismissPopup()
	{
		if (popupWindow != null)
		{
			popupWindow.dismiss();
			popupWindow = null;
		}
	}

	private void handleGo()
	{
		state = State.PREPARATION;
		updateView();

		// create spend
		final SendRequest sendRequest = SendRequest.to(validatedAddress, amountView.getAmount());
		sendRequest.fee = feeView.getAmount();

		backgroundHandler.post(new Runnable()
		{
			public void run()
			{
				//final Transaction transaction = wallet.sendCoinsOffline(sendRequest);
                // Multi-part transaction creation to properly calculate fee
                final Transaction transaction = wallet.createSend(sendRequest);
                int txSize = transaction.getOutputs().size();
                // Get the size of the transaction
                Log.d("Litecoin", "Transaction size is " + txSize);
                /* From official Litecoin wallet.cpp
                    // Check that enough fee is included
                    int64_t nPayFee = nTransactionFee * (1 + (int64_t)nBytes / 1000);
                    bool fAllowFree = AllowFree(dPriority);
                    int64_t nMinFee = GetMinFee(wtxNew, nBytes, fAllowFree, GMF_SEND);
                    if (nFeeRet < max(nPayFee, nMinFee))
                    {
                        nFeeRet = max(nPayFee, nMinFee);
                        continue;
                    }
                    nTransactionFee is the client setting for transaction fees.  This is our default.
                    nMinFee is just the minimum fee.  10000 Satoshis in official source.
                */
                BigInteger nTransactionFee = Constants.DEFAULT_TX_FEE;
                BigInteger nMinFee = Constants.DEFAULT_TX_FEE;
                BigInteger multiplicand = new BigInteger(Integer.toString(1 + txSize / 1000));
                BigInteger nPayFee = nTransactionFee.multiply(multiplicand);
                if(sendRequest.fee.compareTo(nPayFee.max(nMinFee)) < 0)
                {
                    Log.i("LitecoinSendCoins", "Recalculated fee: " +
                            sendRequest.fee.toString() + " < " + nPayFee.max(nMinFee).toString());
                    sendRequest.fee = nPayFee.max(nMinFee);

                new AlertDialog.Builder(SendCoinsFragment.this.getActivity())
                        .setMessage("Transaction requires fee of " +
                                new BigDecimal(sendRequest.fee).divide(new BigDecimal("100000000")) + ".  Continue?")
                        .setTitle("Extra fee needed")
                        .setCancelable(true)
                        .setNeutralButton(android.R.string.cancel,
                                new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface dialog, int whichButton) {
                                        startActivity(new Intent(getActivity(), WalletActivity.class));
                                        getActivity().finish();
                                    }
                                })
                        .setPositiveButton(android.R.string.ok,
                                new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface dialogInterface, int i) {
                                        try {
                                            wallet.commitTx(sendRequest.tx);
                                        } catch (VerificationException e) {
                                            Log.i("LitecoinSendCoins", "VerificationException: " + e);
                                            return;
                                        }
                                        // Fees are agreeable
                                        // Process the transaction
                                        handler.post(new TransactionRunnable(transaction));
                                    }
                                })
                        .show();
                } else {
                    // No fee recalculation necessary
                    // Process the transaction
                    try {
                        wallet.commitTx(sendRequest.tx);
                    } catch (VerificationException e) {
                        Log.i("LitecoinSendCoins", "VerificationException: " + e);
                        return;
                    }
                    handler.post(new TransactionRunnable(transaction));
                }
			}
		});
	}

    class TransactionRunnable implements Runnable {

        Transaction transaction;

        TransactionRunnable(Transaction transaction) {
            this.transaction = transaction;
        }

        public void run()
        {
            sentTransaction = transaction;

            state = State.SENDING;
            updateView();

            sentTransaction.getConfidence().addEventListener(sentTransactionConfidenceListener);

            service.broadcastTransaction(sentTransaction);

            final Intent result = new Intent();
            LitecoinIntegration.transactionHashToResult(result, sentTransaction.getHashAsString());
            activity.setResult(Activity.RESULT_OK, result);

            // final String label = AddressBookProvider.resolveLabel(contentResolver,
            // validatedAddress.toString());
            // if (label == null)
            // showAddAddressDialog(validatedAddress.toString(), receivingLabel);
        }
    }

	private void handleScan()
	{
        IntentIntegrator integrator = new IntentIntegratorSupportV4(this);
        integrator.initiateScan();
	}

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);
        IntentResult scanResult = IntentIntegrator.parseActivityResult(requestCode, resultCode, intent);
        if (scanResult != null) {
            LitecoinURI uri = WalletUtils.parseAddressString(scanResult.getContents());
            if(uri != null)
                update(uri.getAddress().toString(), uri.getLabel(), uri.getAmount());
        }
    }


	public class AutoCompleteAddressAdapter extends CursorAdapter
	{
		public AutoCompleteAddressAdapter(final Context context, final Cursor c)
		{
			super(context, c);
		}

		@Override
		public View newView(final Context context, final Cursor cursor, final ViewGroup parent)
		{
			final LayoutInflater inflater = LayoutInflater.from(context);
			return inflater.inflate(R.layout.address_book_row, parent, false);
		}

		@Override
		public void bindView(final View view, final Context context, final Cursor cursor)
		{
			final String label = cursor.getString(cursor.getColumnIndexOrThrow(AddressBookProvider.KEY_LABEL));
			final String address = cursor.getString(cursor.getColumnIndexOrThrow(AddressBookProvider.KEY_ADDRESS));

			final ViewGroup viewGroup = (ViewGroup) view;
			final TextView labelView = (TextView) viewGroup.findViewById(R.id.address_book_row_label);
			labelView.setText(label);
			final TextView addressView = (TextView) viewGroup.findViewById(R.id.address_book_row_address);
			addressView.setText(WalletUtils.formatHash(address, Constants.ADDRESS_FORMAT_GROUP_SIZE, Constants.ADDRESS_FORMAT_LINE_SIZE));
		}

		@Override
		public CharSequence convertToString(final Cursor cursor)
		{
			return cursor.getString(cursor.getColumnIndexOrThrow(AddressBookProvider.KEY_ADDRESS));
		}

		@Override
		public Cursor runQueryOnBackgroundThread(final CharSequence constraint)
		{
			final Cursor cursor = activity.managedQuery(AddressBookProvider.contentUri(activity.getPackageName()), null,
					AddressBookProvider.SELECTION_QUERY, new String[] { constraint.toString() }, null);
			return cursor;
		}
	}

	private void updateView()
	{
		if (validatedAddress != null)
		{
			receivingAddressView.setVisibility(View.GONE);

			receivingStaticView.setVisibility(View.VISIBLE);
			receivingStaticAddressView.setText(WalletUtils.formatAddress(validatedAddress, Constants.ADDRESS_FORMAT_GROUP_SIZE,
					Constants.ADDRESS_FORMAT_LINE_SIZE));
			final String label = AddressBookProvider.resolveLabel(activity, validatedAddress.toString());
			receivingStaticLabelView.setText(label != null ? label
					: (receivingLabel != null ? receivingLabel : getString(R.string.address_unlabeled)));
			receivingStaticLabelView.setTextColor(label != null ? R.color.fg_significant : R.color.fg_insignificant);
		}
		else
		{
			receivingStaticView.setVisibility(View.GONE);

			receivingAddressView.setVisibility(View.VISIBLE);
		}

		receivingAddressView.setEnabled(state == State.INPUT);

		receivingStaticView.setEnabled(state == State.INPUT);

		amountView.setEnabled(state == State.INPUT);

		feeView.setEnabled(state == State.INPUT);

		if (sentTransaction != null)
		{
			sentTransactionView.setVisibility(View.VISIBLE);
			sentTransactionListAdapter.setPrecision(Integer.parseInt(prefs.getString(Constants.PREFS_KEY_LTC_PRECISION,
					Integer.toString(Constants.LTC_PRECISION))));
			sentTransactionListAdapter.replace(sentTransaction);
		}
		else
		{
			sentTransactionView.setVisibility(View.GONE);
			sentTransactionListAdapter.clear();
		}

		viewCancel.setEnabled(state != State.PREPARATION);
		viewGo.setEnabled(everythingValid());

		if (state == State.INPUT)
		{
			viewCancel.setText(R.string.button_cancel);
			viewGo.setText(R.string.send_coins_fragment_button_send);
		}
		else if (state == State.PREPARATION)
		{
			viewCancel.setText(R.string.button_cancel);
			viewGo.setText(R.string.send_coins_preparation_msg);
		}
		else if (state == State.SENDING)
		{
			viewCancel.setText(R.string.send_coins_fragment_button_back);
			viewGo.setText(R.string.send_coins_sending_msg);
		}
		else if (state == State.SENT)
		{
			viewCancel.setText(R.string.send_coins_fragment_button_back);
			viewGo.setText(R.string.send_coins_sent_msg);
		}
		else if (state == State.FAILED)
		{
			viewCancel.setText(R.string.send_coins_fragment_button_back);
			viewGo.setText(R.string.send_coins_failed_msg);
		}

		if (scanAction != null)
			scanAction.setEnabled(state == State.INPUT);
	}

	private boolean everythingValid()
	{
		return state == State.INPUT && validatedAddress != null && isValidAmounts;
	}

	public void update(final String receivingAddress, final String receivingLabel, final BigInteger amount)
	{
		try
		{
			validatedAddress = new Address(Constants.NETWORK_PARAMETERS, receivingAddress);
			this.receivingLabel = receivingLabel;
		}
		catch (final Exception x)
		{
			receivingAddressView.setText(receivingAddress);
			x.printStackTrace();
		}

		if (amount != null)
			amountView.setAmount(amount);

		// focus
		if (receivingAddress != null && amount == null)
			amountView.requestFocus();
		else if (receivingAddress != null && amount != null)
			feeView.requestFocus();

		updateView();

		handler.postDelayed(new Runnable()
		{
			public void run()
			{
				validateReceivingAddress(true);
				validateAmounts(true);
			}
		}, 500);
	}

	private void showAddAddressDialog(final String address, final String suggestedAddressLabel)
	{
		final AlertDialog.Builder builder = new AlertDialog.Builder(activity);
		builder.setMessage(R.string.send_coins_add_address_dialog_title);
		builder.setPositiveButton(R.string.send_coins_add_address_dialog_button_add, new DialogInterface.OnClickListener()
		{
			public void onClick(final DialogInterface dialog, final int id)
			{
				EditAddressBookEntryFragment.edit(getFragmentManager(), address, suggestedAddressLabel);
			}
		});
		builder.setNegativeButton(R.string.button_dismiss, null);
		builder.show();
	}

	public void useCalculatedAmount(final BigInteger amount)
	{
		amountView.setAmount(amount);
	}
}
