package org.isotel;

import jssc.SerialPortEvent;
import jssc.SerialPortException;
import processing.app.PreferencesData;
import processing.app.Serial;
import processing.app.SerialException;

import java.io.IOException;

public class IdmSerial extends Serial {

	private SNFramer snFramer;

	public IdmSerial(String iname, int irate) throws SerialException {
		super(iname, irate);

		snFramer = new SNFramer(this);
	}

	@Override
	public synchronized void serialEvent(SerialPortEvent serialEvent) {
		if (serialEvent.isRXCHAR()) {
			try {
				byte[] buf = port.readBytes(serialEvent.getEventValue());

				if (buf != null) {
					if (snFramer != null)
						snFramer.processData(buf, 0, buf.length);
					else
						processCharData(buf);
				}

			} catch (SerialPortException e) {
				errorMessage("serialEvent", e);
			}
		}
	}

	@Override
	public void dispose() throws IOException {
		super.dispose();
		if (snFramer != null)
			snFramer.close();
	}

	protected void processCharData(byte[] buf) {

		int next = 0;
		while (next < buf.length) {
			while (next < buf.length && outToMessage.hasRemaining()) {
				int spaceInIn = inFromSerial.remaining();
				int copyNow = buf.length - next < spaceInIn ? buf.length - next : spaceInIn;
				inFromSerial.put(buf, next, copyNow);
				next += copyNow;
				inFromSerial.flip();
				bytesToStrings.decode(inFromSerial, outToMessage, false);
				inFromSerial.compact();
			}
			outToMessage.flip();
			if (outToMessage.hasRemaining()) {
				char[] chars = new char[outToMessage.remaining()];
				outToMessage.get(chars);
				message(chars, chars.length);
			}
			outToMessage.clear();
		}
	}

	@Override
	protected void message(char[] chars, int length) {
		// Empty

	}

	static class SNFramer {

		public static final int ID_LIMIT_LOW = 0x80;
		public static final int ID_LIMIT_HIGH = 0xBF;
		private static final char[] hexArray = { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E',
		'F' };
		protected final static int BUFFER_SIZE = 1024;
		private final IdmSerial serial;
		private final IdmUDPHandler udpHandler;
		private final CRCHandler crcHandler;
		protected byte[] receiveBuffer = new byte[BUFFER_SIZE];
		protected int receiveSize = 0;
		protected int frameTimeout = 1000;
		private int readIndex = 0;
		private int frameStart = -1;
		private int frameEnd = -1;
		private int expectedLength = 0;
		private long lastReceivedFrame = 0;
		private int frameCount = 0;
		private int crcErrorCount = 0;

		/**
		 * @param serial
		 *          callback reference
		 */
		public SNFramer(IdmSerial serial) {

			this.serial = serial;
			udpHandler = new IdmUDPHandler(this);
			crcHandler = new CRCHandler();
			
			udpHandler.connect(PreferencesData.getNonEmpty("idm.udp.host", IdmUDPHandler.DEFAULT_HOST),
					PreferencesData.getInteger("idm.udp.port", IdmUDPHandler.DEFAULT_PORT));
		}

		public static String bytesToHex(final byte[] bytes, int limit) {

			byte[] hexChars = new byte[limit * 3 - 1];
			int v;
			for (int j = 0; (j < bytes.length && j < limit); j++) {
				v = bytes[j] & 0xFF;
				hexChars[j * 3] = (byte) hexArray[v >>> 4];
				hexChars[j * 3 + 1] = (byte) hexArray[v & 0x0F];
				if (j * 3 + 2 < hexChars.length)
					hexChars[j * 3 + 2] = (byte) ' ';
			}
			return new String(hexChars);
		}

		/**
		 * @param data
		 * @param inx
		 * @param length
		 * @return
		 */
		public boolean processData(byte[] data, int inx, int length) {

			if (data != null && data.length > 0) {

				if (System.currentTimeMillis() - lastReceivedFrame > frameTimeout) {
					dumpChar(receiveBuffer, readIndex, receiveSize - readIndex);
					frameStart = -1;
					receiveSize = 0;
					readIndex = 0;

					report("Frame timeout");
					lastReceivedFrame = System.currentTimeMillis();
				}

				if (receiveSize + length > BUFFER_SIZE) {
					receiveSize = receiveSize - readIndex;

					System.arraycopy(receiveBuffer, readIndex, receiveBuffer, 0, receiveSize);

					readIndex = 0;
					if (frameStart >= 0) {
						frameStart = 0;
						frameEnd = expectedLength;
					}

				}

				System.arraycopy(data, inx, receiveBuffer, receiveSize, length);
				receiveSize += length;

				boolean res = true;
				while (res) {
					res = process();
				}

			}
			return false;
		}

		/**
		 * Called when received data from network
		 *
		 * @param data
		 * @return
		 */
		public int onDataReceived(byte[] data) {

			serial.write(data);

			return 0;
		}

		/**
		 *
		 */
		public void close() {
			udpHandler.disconnect();
		}

		/**
		 * @return
		 */
		private boolean process() {

			boolean res = false;
			if (frameStart < 0) {

				for (int i = readIndex; i < receiveSize; i++) {
					int id = 0xFF & (int) receiveBuffer[i];

					if (isValidProtocolID(id)) {
						frameStart = i;
						expectedLength = (0x3F & id) + 3;
						frameEnd = frameStart + expectedLength;

						break;
					} else
						dumpChar(receiveBuffer, i, 1);
					readIndex++;
				}
			}

			if (frameStart >= 0 && receiveSize > frameEnd) {
				// Have enough data, check frame consistency

				byte crc = crcHandler.Checksum(receiveBuffer, frameStart, frameEnd - 1);

				if (crc != receiveBuffer[frameEnd - 1]) {

					crcErrorCount++;
					dumpChar(receiveBuffer, frameStart, 1);
					readIndex++;

				} else {

					byte[] frame = new byte[expectedLength];
					System.arraycopy(receiveBuffer, frameStart, frame, 0, expectedLength);

					if (udpHandler.isConnected()) {
						udpHandler.sendFrame(frame, frame.length);
					}

					readIndex = frameEnd;

					frameCount++;
					lastReceivedFrame = System.currentTimeMillis();

				}

				frameStart = -1;

				res = true;
			}

			return res;
		}

		private boolean isValidProtocolID(int protocolID) {
			if (protocolID > ID_LIMIT_LOW && protocolID <= ID_LIMIT_HIGH)
				return true;

			return false;
		}

		private void dumpChar(byte[] buffer, int inx, int length) {

			if (length > 0) {
				byte[] data = new byte[length];
				System.arraycopy(buffer, inx, data, 0, length);
				serial.processCharData(data);
			}
		}

		private static void report(String message) {
			System.out.println(message);
		}

		/**
		 * CRC Calculation class
		 */
		private static class CRCHandler {

			private static final int SNCF_CRC8_POLYNOMIAL = 0x4D;			
			private static byte[] table;

			CRCHandler() {
				table = GenerateTable(SNCF_CRC8_POLYNOMIAL);
			}

			/**
			 * @param val
			 * @param start
			 *          index
			 * @param end
			 *          -1 index
			 * @return
			 */
			public byte Checksum(byte[] val, int start, int end) {
				if (val == null)
					throw new NullPointerException("Null");

				byte c = 0;

				for (int i = start; i < end; i++) {

					c = table[(int) (c & 0xFF) ^ (int) (val[i] & 0xFF)];
				}

				return c;
			}

			public byte[] GenerateTable(int polynomial) {

				byte[] csTable = new byte[256];

				for (int i = 0; i < 256; ++i) {
					int curr = i;

					for (int j = 0; j < 8; ++j) {
						if ((curr & 0x80) != 0) {
							curr = (curr << 1) ^ (int) polynomial;
						} else {
							curr <<= 1;
						}
					}

					csTable[i] = (byte) curr;

				}

				return csTable;
			}
		}
	}

}
