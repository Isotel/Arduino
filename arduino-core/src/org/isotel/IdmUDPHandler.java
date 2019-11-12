package org.isotel;

import org.isotel.IdmSerial.ISNCompactFrame;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.StandardSocketOptions;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * UDP communication manager
 *
 * @author TK
 */
public class IdmUDPHandler {

	public static final String DEFAULT_HOST = "localhost";
	public static final int DEFAULT_PORT = 33005;
	private final ScheduledExecutorService executor;
	private DatagramChannel client = null;
	private SocketAddress address = null;
	private String host = null;
	private int port = 0;
	private boolean connected = false;
	private boolean connecting = false;
	private ISNCompactFrame callback;
	private long lastConnected = 0;
	private int reconnectInterval = 2;

	public IdmUDPHandler(ISNCompactFrame callback) {
		this.callback = callback;
		executor = Executors.newSingleThreadScheduledExecutor();
		report("SN Protocol packet forwarding mode is enabled through UDP");
	}

	/**
	 * @param host
	 * @param port
	 * @return
	 */
	public int connect(String host, int port) {
		try {
			connecting = true;
			this.host = host;
			this.port = port;
			String hostname = host + ":" + port;
			
			report("Connecting UDP client to " + hostname);
			client = DatagramChannel.open();
			address = new InetSocketAddress(host, port);
			client.connect(address);
			client.setOption(StandardSocketOptions.SO_REUSEADDR, true);
			client.configureBlocking(false);
			connected = true;
			lastConnected = System.currentTimeMillis();
			connecting = false;
			
			report("Connected UDP client to " + hostname);

			Runnable r = new UDPRunnable();
			Thread t = new Thread(r);
			t.start();
			return 0;

		} catch (SocketException e) {
			report("UDP Socket exception: " + e.getMessage());
		} catch (UnknownHostException e) {
			report("UDP Unknow Host exception: " + e.getMessage());
		} catch (IOException e1) {
			report("UDP IO exception: " + e1.getMessage());
		}
		report("Unable to connect UDP to " + this.getID());
		connected = false;
		connecting = false;
		scheduleReconnectAttempt();
		return -1;
	}

	/**
	 * @return
	 */
	public String getID() {
		return "UDP client";
	}

	public boolean isConnected() {
		return connected;
	}

	public int disconnect() {
		try {
			executor.shutdown();
			if (connected) {
				client.close();
				connected = false;
				return 0;
			}
		} catch (Exception e) {
			report("Exception while disconnecting UDP client: " + e.getMessage());
		}
		return -1;
	}
	
	private int disconnectOnError() {
		try {			
			if (connected) {
				client.close();
				connected = false;
				
			}
		} catch (Exception e) {
			report("Exception while disconnecting UDP client: " + e.getMessage());
		}
		if (System.currentTimeMillis() - this.lastConnected > 1800)
			reconnectInterval = 2;
		scheduleReconnectAttempt();
		return 0;
	}

	private void scheduleReconnectAttempt() {
		if (!executor.isShutdown() && !executor.isTerminated()) {
			report(String.format("UDP reconnect attempt scheduled after %d seconds", reconnectInterval));
			executor.schedule(this::reconnect, reconnectInterval, TimeUnit.SECONDS);
			if (reconnectInterval < 64)
				reconnectInterval *=2;
		}
	}
	
	private int reconnect() {
		if (!connected && !connecting) {
			connect(host,port);
		}
		return -1;
	}

	/**
	 * @param data
	 * @param inx
	 * @param length
	 * @return
	 */
	public int sendFrame(byte[] data, int length) {
		write(data, length);
		return 0;
	}

	/**
	 * Write UDP packet
	 *
	 * @param data
	 * @param length
	 */
	protected void write(final byte[] data, final int length) {
		if (connected) {
			try {
				ByteBuffer buffer = ByteBuffer.wrap(data);
				client.send(buffer, address);
			} catch (IOException e) {				
				report("IO exception: " + e.getMessage());
			}
		}
	}

	private static void report(String message) {
		System.out.println(message);
	}

	private class UDPRunnable implements Runnable {
		@Override
		public void run() {
			while (connected) {
				try {
					byte[] buf = new byte[512];
					ByteBuffer buffer = ByteBuffer.wrap(buf);
					SocketAddress addr = client.receive(buffer);
					
					if (addr != null) {
						buffer.flip();
						int limits = buffer.limit();
						byte bytes[] = new byte[limits];
						buffer.get(bytes, 0, limits);
						callback.onDataReceived(bytes);
					}
					Thread.sleep(10);
				} catch (java.net.SocketTimeoutException e) {
					continue;
				} catch (SocketException se) {
					
					report("UDP Socket exception: " + se.getMessage());
					disconnectOnError();
					break;
				} catch (Exception oe) {
					
					report("UDP connection exception: " + oe.getMessage());
					disconnectOnError();
					break;
				}
			}
		}
	}

} 
