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

import java.util.ArrayList;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;
import android.support.v4.widget.SimpleCursorAdapter;
import android.support.v4.widget.SimpleCursorAdapter.ViewBinder;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import android.widget.TextView;

import android.widget.Toast;
import com.actionbarsherlock.app.SherlockListFragment;
import com.actionbarsherlock.view.ActionMode;
import com.actionbarsherlock.view.Menu;
import com.actionbarsherlock.view.MenuInflater;
import com.actionbarsherlock.view.MenuItem;
import com.google.litecoin.core.Address;
import com.google.litecoin.core.AddressFormatException;
import com.google.litecoin.uri.LitecoinURI;
import com.google.litecoin.uri.LitecoinURIParseException;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentIntegratorSupportV4;
import com.google.zxing.integration.android.IntentResult;
import de.schildbach.wallet.litecoin.AddressBookProvider;
import de.schildbach.wallet.litecoin.Constants;
import de.schildbach.wallet.litecoin.util.BitmapFragment;
import de.schildbach.wallet.litecoin.util.WalletUtils;
import de.schildbach.wallet.litecoin.R;

/**
 * @author Andreas Schildbach
 */
public final class SendingAddressesFragment extends SherlockListFragment implements LoaderManager.LoaderCallbacks<Cursor>
{
	private AbstractWalletActivity activity;
    private android.text.ClipboardManager clipboardManager;
	private LoaderManager loaderManager;

	private SimpleCursorAdapter adapter;
	private String walletAddressesSelection;

	private final Handler handler = new Handler();

	private static final int REQUEST_CODE_SCAN = 0;
    private Address addressToAdd = null;

    @Override
	public void onAttach(final Activity activity)
	{
		super.onAttach(activity);

		this.activity = (AbstractWalletActivity) activity;
        // This works for both clipboard managers because android.content.ClipboardManager is subclassed from the old one.
        this.clipboardManager = (android.text.ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
		this.loaderManager = getLoaderManager();
	}

	@Override
	public void onCreate(final Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);

		setHasOptionsMenu(true);
	}

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return super.onCreateView(inflater, container, savedInstanceState);
    }

    @Override
	public void onActivityCreated(final Bundle savedInstanceState)
	{
		super.onActivityCreated(savedInstanceState);

		setEmptyText(getString(R.string.address_book_empty_text));

		adapter = new SimpleCursorAdapter(activity, R.layout.address_book_row, null, new String[] { AddressBookProvider.KEY_LABEL,
				AddressBookProvider.KEY_ADDRESS }, new int[] { R.id.address_book_row_label, R.id.address_book_row_address }, 0);
		adapter.setViewBinder(new ViewBinder()
		{
			public boolean setViewValue(final View view, final Cursor cursor, final int columnIndex)
			{
				if (!AddressBookProvider.KEY_ADDRESS.equals(cursor.getColumnName(columnIndex)))
					return false;

				((TextView) view).setText(WalletUtils.formatHash(cursor.getString(columnIndex), Constants.ADDRESS_FORMAT_GROUP_SIZE,
						Constants.ADDRESS_FORMAT_LINE_SIZE));

				return true;
			}
		});
		setListAdapter(adapter);

		loaderManager.initLoader(0, null, this);
	}

    @Override
    public void onResume() {
        super.onResume();
        if(null != this.addressToAdd)
        {
            // Starting address to add dialog
            EditAddressBookEntryFragment.edit(getFragmentManager(), this.addressToAdd.toString());
        }
    }

    @Override
	public void onActivityResult(final int requestCode, final int resultCode, final Intent intent)
	{
        super.onActivityResult(requestCode, resultCode, intent);
        Log.i(this.getClass().toString(), "on Scan Activity Result");
        IntentResult scanResult = IntentIntegrator.parseActivityResult(requestCode, resultCode, intent);
        if(scanResult != null) {
            LitecoinURI uri = WalletUtils.parseAddressString(scanResult.getContents());
            if(uri != null) {
                try
                {
                    this.addressToAdd = new Address(Constants.NETWORK_PARAMETERS, uri.getAddress().toString());

                }
                catch (final AddressFormatException x)
                {
                    activity.toast(R.string.send_coins_parse_address_error_msg);
                }
            }
        }
    }

	@SuppressLint("InlinedApi")
	@Override
	public void onCreateOptionsMenu(final Menu menu, final MenuInflater inflater)
	{
		inflater.inflate(R.menu.sending_addresses_fragment_options, menu);

		final PackageManager pm = activity.getPackageManager();
		menu.findItem(R.id.sending_addresses_options_scan).setVisible(
				pm.hasSystemFeature(PackageManager.FEATURE_CAMERA) || pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT));

		super.onCreateOptionsMenu(menu, inflater);
	}

	@Override
	public boolean onOptionsItemSelected(final MenuItem item)
	{
		switch (item.getItemId())
		{
			case R.id.sending_addresses_options_paste:
				handlePasteClipboard();
				return true;

			case R.id.sending_addresses_options_scan:
				handleScan();
				return true;
		}

		return super.onOptionsItemSelected(item);
	}

	private void handlePasteClipboard()
	{
		if (clipboardManager.hasText())
		{
			final String text = clipboardManager.getText().toString().trim();

			try
			{
				final Address address = new Address(Constants.NETWORK_PARAMETERS, text);
				EditAddressBookEntryFragment.edit(getFragmentManager(), address.toString());
			}
			catch (final AddressFormatException x)
			{
				activity.toast(R.string.send_coins_parse_address_error_msg);
			}
		}
		else
		{
			activity.toast(R.string.address_book_options_copy_from_clipboard_msg_empty);
		}
	}

	private void handleScan()
	{
            IntentIntegrator integrator = new IntentIntegratorSupportV4(this);
            integrator.initiateScan();
    }

	@Override
	public void onListItemClick(final ListView l, final View v, final int position, final long id)
	{
		activity.startActionMode(new ActionMode.Callback()
		{
			public boolean onCreateActionMode(final ActionMode mode, final Menu menu)
			{
				final MenuInflater inflater = mode.getMenuInflater();
				inflater.inflate(R.menu.sending_addresses_context, menu);

				return true;
			}

			public boolean onPrepareActionMode(final ActionMode mode, final Menu menu)
			{
				final String label = getLabel(position);
				mode.setTitle(label);

				return true;
			}

			public boolean onActionItemClicked(final ActionMode mode, final MenuItem item)
			{
				switch (item.getItemId())
				{
					case R.id.sending_addresses_context_send:
						handleSend(getAddress(position));

						mode.finish();
						return true;

					case R.id.sending_addresses_context_edit:
						EditAddressBookEntryFragment.edit(getFragmentManager(), getAddress(position));

						mode.finish();
						return true;

					case R.id.sending_addresses_context_remove:
						handleRemove(getAddress(position));

						mode.finish();
						return true;

					case R.id.sending_addresses_context_show_qr:
						handleShowQr(getAddress(position));

						mode.finish();
						return true;

					case R.id.sending_addresses_context_copy_to_clipboard:
						handleCopyToClipboard(getAddress(position));

						mode.finish();
						return true;
				}

				return false;
			}

			public void onDestroyActionMode(final ActionMode mode)
			{
			}

			private String getAddress(final int position)
			{
				final Cursor cursor = (Cursor) adapter.getItem(position);
				return cursor.getString(cursor.getColumnIndexOrThrow(AddressBookProvider.KEY_ADDRESS));
			}

			private String getLabel(final int position)
			{
				final Cursor cursor = (Cursor) adapter.getItem(position);
				return cursor.getString(cursor.getColumnIndexOrThrow(AddressBookProvider.KEY_LABEL));
			}
		});
	}

	private void handleSend(final String address)
	{
		final Intent intent = new Intent(activity, SendCoinsActivity.class);
		intent.putExtra(SendCoinsActivity.INTENT_EXTRA_ADDRESS, address);
		startActivity(intent);
	}

	private void handleRemove(final String address)
	{
		final Uri uri = AddressBookProvider.contentUri(activity.getPackageName()).buildUpon().appendPath(address).build();
		activity.getContentResolver().delete(uri, null, null);
	}

	private void handleShowQr(final String address)
	{
		final String uri = LitecoinURI.convertToLitecoinURI(address, null, null, null);
		final int size = (int) (256 * getResources().getDisplayMetrics().density);
		BitmapFragment.show(getFragmentManager(), WalletUtils.getQRCodeBitmap(uri, size));
	}

	private void handleCopyToClipboard(final String address)
	{
        int sdk = android.os.Build.VERSION.SDK_INT;
        if(sdk < android.os.Build.VERSION_CODES.HONEYCOMB) {
            android.text.ClipboardManager clipboard = (android.text.ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setText(address);
        } else {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            android.content.ClipData clip = android.content.ClipData.newPlainText("address", address);
            clipboard.setPrimaryClip(clip);
        }

		activity.toast(R.string.wallet_address_fragment_clipboard_msg);
	}

	public Loader<Cursor> onCreateLoader(final int id, final Bundle args)
	{
		final Uri uri = AddressBookProvider.contentUri(activity.getPackageName());
		return new CursorLoader(activity, uri, null, AddressBookProvider.SELECTION_NOTIN,
				new String[] { walletAddressesSelection != null ? walletAddressesSelection : "" }, AddressBookProvider.KEY_LABEL
						+ " COLLATE LOCALIZED ASC");
	}

	public void onLoadFinished(final Loader<Cursor> loader, final Cursor data)
	{
		adapter.swapCursor(data);
	}

	public void onLoaderReset(final Loader<Cursor> loader)
	{
		adapter.swapCursor(null);
	}

	public void setWalletAddresses(final ArrayList<Address> addresses)
	{
		final StringBuilder builder = new StringBuilder();
		for (final Address address : addresses)
			builder.append(address.toString()).append(",");
		if (addresses.size() > 0)
			builder.setLength(builder.length() - 1);

		walletAddressesSelection = builder.toString();
	}
}
