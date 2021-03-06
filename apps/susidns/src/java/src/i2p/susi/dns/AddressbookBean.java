/*
 * Created on Sep 02, 2005
 * 
 *  This file is part of susidns project, see http://susi.i2p/
 *  
 *  Copyright (C) 2005 <susi23@mail.i2p>
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 *  
 * $Revision: 1.2 $
 */

package i2p.susi.dns;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import net.i2p.data.DataFormatException;
import net.i2p.data.DataHelper;
import net.i2p.data.Destination;
import net.i2p.util.SecureFileOutputStream;

public class AddressbookBean extends BaseBean
{
	protected String book, filter, search, hostname, destination;
	protected int beginIndex, endIndex;
	private Properties addressbook;
	private int trClass;
	protected final LinkedList<String> deletionMarks;
	protected static final Comparator<AddressBean> sorter;
	private static final int DISPLAY_SIZE = 50;

	static {
		sorter = new AddressByNameSorter();
	}
	public String getSearch() {
		return search;
	}
	public void setSearch(String search) {
		this.search = DataHelper.stripHTML(search).trim();  // XSS;
	}
	public boolean isHasFilter()
	{
		return filter != null && filter.length() > 0;
	}
	public void setTrClass(int trClass) {
		this.trClass = trClass;
	}
	public int getTrClass() {
		trClass = 1 - trClass;
		return trClass;
	}
	public boolean isIsEmpty()
	{
		return ! isNotEmpty();
	}
	public boolean isNotEmpty()
	{
		return addressbook != null && !addressbook.isEmpty();
	}

	public AddressbookBean()
	{
		super();
		deletionMarks = new LinkedList<String>();
		beginIndex = 0;
		endIndex = DISPLAY_SIZE - 1;
	}

	public String getFileName()
	{
		loadConfig();
		String filename = properties.getProperty( getBook() + "_addressbook" );
		// clean up the ../ with getCanonicalPath()
		File path = new File(addressbookDir(), filename);
		try {
			return path.getCanonicalPath();
		} catch (IOException ioe) {}
		return filename;
	}

	public String getDisplayName()
	{
		return getFileName();
	}

	protected AddressBean[] entries;

	public AddressBean[] getEntries()
	{
		return entries;
	}

	public String getBook()
	{
		if( book == null || ( !book.equalsIgnoreCase( "master" ) &&
				!book.equalsIgnoreCase( "router" ) &&
				!book.equalsIgnoreCase( "private" ) &&
				!book.equalsIgnoreCase( "published" )))
			book = "router";
		
		return book;
	}
	public void setBook(String book) {
		this.book = DataHelper.stripHTML(book);  // XSS
	}

	/** Load addressbook and apply filter, returning messages about this. */
	public String getLoadBookMessages()
	{
		// Config and addressbook now loaded here, hence not needed in getMessages()
		loadConfig();
		addressbook = new Properties();
		
		String message = "";
		FileInputStream fis = null;
		try {
			fis =  new FileInputStream( getFileName() );
			addressbook.load( fis );
			LinkedList<AddressBean> list = new LinkedList<AddressBean>();
			for( Map.Entry<Object, Object> entry : addressbook.entrySet() ) {
				String name = (String) entry.getKey();
				String destination = (String) entry.getValue();
				if( filter != null && filter.length() > 0 ) {
					if( filter.compareTo( "0-9" ) == 0 ) {
						char first = name.charAt(0);
						if( first < '0' || first > '9' )
							continue;
					}
					else if( ! name.toLowerCase(Locale.US).startsWith( filter.toLowerCase(Locale.US) ) ) {
						continue;
					}
				}
				if( search != null && search.length() > 0 ) {
					if( name.indexOf( search ) == -1 ) {
						continue;
					}
				}
				list.addLast( new AddressBean( name, destination ) );
			}
			AddressBean array[] = list.toArray(new AddressBean[list.size()]);
			Arrays.sort( array, sorter );
			entries = array;

			message = generateLoadMessage();
		}
		catch (Exception e) {
			warn(e);
		} finally {
			if (fis != null)
				try { fis.close(); } catch (IOException ioe) {}
		}
		if( message.length() > 0 )
			message = "<p>" + message + "</p>";
		return message;
	}

	/**
	 *  Format a message about filtered addressbook size, and the number of displayed entries
	 *  addressbook.jsp catches the case where the whole book is empty.
	 */
	protected String generateLoadMessage() {
		String message;
		String filterArg = "";
		int resultCount = resultSize();
		if( filter != null && filter.length() > 0 ) {
			if( search != null && search.length() > 0 )
				message = ngettext("One result for search within filtered list.",
				                   "{0} results for search within filtered list.",
				                   resultCount);
			else
				message = ngettext("Filtered list contains 1 entry.",
				                   "Filtered list contains {0} entries.",
				                   resultCount);
			filterArg = "&amp;filter=" + filter;
		} else if( search != null && search.length() > 0 ) {
			message = ngettext("One result for search.",
			                   "{0} results for search.",
			                   resultCount);
		} else {
			if (resultCount <= 0)
				// covered in jsp
				//message = _t("This addressbook is empty.");
				message = "";
			else
				message = ngettext("Address book contains 1 entry.",
				                   "Address book contains {0} entries.",
				                   resultCount);
		}
		if (resultCount <= 0) {
			// nothing to display
		} else if (getBeginInt() == 0 && getEndInt() == resultCount - 1) {
			// nothing to display
		} else {
			if (getBeginInt() > 0) {
				int newBegin = Math.max(0, getBeginInt() - DISPLAY_SIZE);
				int newEnd = Math.max(0, getBeginInt() - 1);
		       		message += " <a href=\"addressbook?book=" + getBook() + filterArg +
				           "&amp;begin=" + newBegin + "&amp;end=" + newEnd + "\">" + (newBegin+1) +
				           '-' + (newEnd+1) + "</a> | ";
	       		}
			message += ' ' + _t("Showing {0} of {1}", "" + (getBeginInt()+1) + '-' + (getEndInt()+1), Integer.valueOf(resultCount));
			if (getEndInt() < resultCount - 1) {
				int newBegin = Math.min(resultCount - 1, getEndInt() + 1);
				int newEnd = Math.min(resultCount, getEndInt() + DISPLAY_SIZE);
		       		message += " | <a href=\"addressbook?book=" + getBook() + filterArg +
				           "&amp;begin=" + newBegin + "&amp;end=" + newEnd + "\">" + (newBegin+1) +
				           '-' + (newEnd+1) + "</a>";
			}
		}
		return message;
	}

	/** Perform actions, returning messages about this. */
	public String getMessages()
	{
		// Loading config and addressbook moved into getLoadBookMessages()
		String message = "";
		
		if( action != null ) {
                        if (_context.getBooleanProperty(PROP_PW_ENABLE) ||
			    (serial != null && serial.equals(lastSerial))) {
				boolean changed = false;
				if (action.equals(_t("Add")) || action.equals(_t("Replace"))) {
					if( addressbook != null && hostname != null && destination != null ) {
						try {
							// throws IAE with translated message
							String host = AddressBean.toASCII(hostname);
							String displayHost = host.equals(hostname) ? hostname :
							                                             hostname + " (" + host + ')';

							String oldDest = (String) addressbook.get(host);
							if (destination.equals(oldDest)) {
								message = _t("Host name {0} is already in address book, unchanged.", displayHost);
							} else if (oldDest != null && !action.equals(_t("Replace"))) {
								message = _t("Host name {0} is already in address book with a different destination. Click \"Replace\" to overwrite.", displayHost);
							} else {
								boolean valid = true;
								try {
									// just to check validity
									new Destination(destination);
								} catch (DataFormatException dfe) {
									valid = false;
								}
								if (valid) {
									addressbook.put( host, destination );
									changed = true;
									if (oldDest == null)
										message = _t("Destination added for {0}.", displayHost);
									else
										message = _t("Destination changed for {0}.", displayHost);
									if (!host.endsWith(".i2p"))
										message += "<br>" + _t("Warning - host name does not end with \".i2p\"");
									// clear form
									hostname = null;
									destination = null;
								} else {
									message = _t("Invalid Base 64 destination.");
								}
							}
						} catch (IllegalArgumentException iae) {
							message = iae.getMessage();
							if (message == null)
								message = _t("Invalid host name \"{0}\".", hostname);
						}
					} else {
						message = _t("Please enter a host name and destination");
					}
					// clear search when adding
					search = null;
				} else if (action.equals(_t("Delete Selected")) || action.equals(_t("Delete Entry"))) {
					String name = null;
					int deleted = 0;
					for (String n : deletionMarks) {
						addressbook.remove(n);
						String uni = AddressBean.toUnicode(n);
						String displayHost = uni.equals(n) ? n :  uni + " (" + n + ')';
						if (deleted++ == 0) {
							changed = true;
							name = displayHost;
						}
					}
					if( changed ) {
						if (deleted == 1)
							message = _t("Destination {0} deleted.", name);
						else
							// parameter will always be >= 2
							message = ngettext("1 destination deleted.", "{0} destinations deleted.", deleted);
					} else {
						message = _t("No entries selected to delete.");
					}
					if (action.equals(_t("Delete Entry")))
						search = null;
				}
				if( changed ) {
					try {
						save();
						message += "<br>" + _t("Address book saved.");
					} catch (Exception e) {
						warn(e);
						message += "<br>" + _t("ERROR: Could not write addressbook file.");
					}
				}
			}			
			else {
				message = _t("Invalid form submission, probably because you used the \"back\" or \"reload\" button on your browser. Please resubmit.")
                                          + ' ' +
                                          _t("If the problem persists, verify that you have cookies enabled in your browser.");
			}
		}
		
		action = null;
		
		if( message.length() > 0 )
			message = "<p class=\"messages\">" + message + "</p>";
		return message;
	}

	private void save() throws IOException
	{
		String filename = properties.getProperty( getBook() + "_addressbook" );
		
		FileOutputStream fos = null;
		try {
			fos = new SecureFileOutputStream(new File(addressbookDir(), filename));
			addressbook.store( fos, null );
		} finally {
			if (fos != null) {
				try {
					fos.close();
				} catch (IOException ioe) {}
			}
		}
	}

	public String getFilter() {
		return filter;
	}

	public boolean isMaster()
	{
		return getBook().equalsIgnoreCase("master");
	}
	public boolean isRouter()
	{
		return getBook().equalsIgnoreCase("router");
	}
	public boolean isPublished()
	{
		return getBook().equalsIgnoreCase("published");
	}
	public boolean isPrivate()
	{
		return getBook().equalsIgnoreCase("private");
	}
	public void setFilter(String filter) {
		if( filter != null && ( filter.length() == 0 || filter.equalsIgnoreCase("none"))) {
			filter = null;
			search = null;
		}
		this.filter = DataHelper.stripHTML(filter);  // XSS
	}
	public String getDestination() {
		return destination;
	}
	public void setDestination(String destination) {
		this.destination = DataHelper.stripHTML(destination).trim();  // XSS
	}
	public String getHostname() {
		return hostname;
	}
	public void setResetDeletionMarks( String dummy ) {
		deletionMarks.clear();
	}
	public void setMarkedForDeletion( String name ) {
		deletionMarks.addLast( DataHelper.stripHTML(name) );    // XSS
	}
	public void setHostname(String hostname) {
		this.hostname = DataHelper.stripHTML(hostname).trim();  // XSS
	}

	protected int getBeginInt() {
		return Math.max(0, Math.min(resultSize() - 1, beginIndex));
	}

	public String getBegin() {
		return "" + getBeginInt();
	}

	/**
	 *  @return beginning index into results
	 *  @since 0.8.7
	 */
	public String getResultBegin() {
		return isPrefiltered() ? "0" : Integer.toString(getBeginInt());
	}

	public void setBegin(String s) {
		try {
			beginIndex = Integer.parseInt(s);
		} catch (NumberFormatException nfe) {}
	}

	protected int getEndInt() {
		return Math.max(0, Math.max(getBeginInt(), Math.min(resultSize() - 1, endIndex)));
	}

	public String getEnd() {
		return "" + getEndInt();
	}

	/**
	 *  @return ending index into results
	 *  @since 0.8.7
	 */
	public String getResultEnd() {
		return Integer.toString(isPrefiltered() ? resultSize() - 1 : getEndInt());
	}

	public void setEnd(String s) {
		try {
			endIndex = Integer.parseInt(s);
		} catch (NumberFormatException nfe) {}
	}

	/**
	 *  Does the entries map contain only the lookup result,
	 *  or must we index into it?
	 *  @since 0.8.7
	 */
	protected boolean isPrefiltered() {
		return false;
	}

	/**
	 *  @return the size of the lookup result
	 *  @since 0.8.7
	 */
	protected int resultSize() {
		return entries.length;
	}

	/**
	 *  @return the total size of the address book
	 *  @since 0.8.7
	 */
	protected int totalSize() {
		return entries.length;
	}
}
