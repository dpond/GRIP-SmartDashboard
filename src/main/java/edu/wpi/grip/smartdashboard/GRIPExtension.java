package edu.wpi.grip.smartdashboard;

import edu.wpi.first.smartdashboard.gui.StaticWidget;
import edu.wpi.first.smartdashboard.properties.IntegerProperty;
import edu.wpi.first.smartdashboard.properties.Property;
import edu.wpi.first.smartdashboard.properties.StringProperty;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.Socket;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.logging.StreamHandler;

public class GRIPExtension extends StaticWidget {

    public final static String NAME = "GRIP Output Viewer";

    private final static Logger logger = Logger.getLogger(GRIPExtension.class.getName());

    private final static int PORT = 1180;
    private final static byte[] MAGIC_NUMBERS = {0x01, 0x00, 0x00, 0x00};
    private final static int HW_COMPRESSION = -1;
    private final static int SIZE_640x480 = 0;

    private BufferedImage image = null;
    private final Object imageLock = new Object();

    private Thread thread;
    private String error = "";
    private boolean shutdown = false;

    public final IntegerProperty fpsProperty = new IntegerProperty(this, "FPS", 30);
    public final StringProperty addressProperty = new StringProperty(this, "GRIP Address", "localhost");

    @Override
    public void init() {
        setPreferredSize(new Dimension(640, 480));

        logger.addHandler(new StreamHandler(new FileOutputStream(FileDescriptor.err), new SimpleFormatter()));

        thread = new Thread(() -> {
            byte[] magic = new byte[4];
            byte[] imageBuffer = new byte[64 * 1024];

            // Loop until the widget is removed or SmartDashboard is closed.  The outer loop only completes an
            // iteration when the thread is interrupted or an exception happens.
            while (!shutdown) {
                try {
                    try (Socket socket = new Socket(addressProperty.getValue(), PORT);
                         DataInputStream inputStream = new DataInputStream(socket.getInputStream());
                         DataOutputStream outputStream = new DataOutputStream(socket.getOutputStream())) {

                        logger.info("Established connection to " + socket.getInetAddress());
                        error = "";

                        // In the FRC dashboard protocol, the client (us) starts out by sending three 32-bit integers
                        // (FPS, compression level, and a size enum).  FPS is the only one actually recognized by
                        // GRIP.
                        outputStream.writeInt(fpsProperty.getValue());
                        outputStream.writeInt(HW_COMPRESSION);
                        outputStream.writeInt(SIZE_640x480);

                        while (!Thread.currentThread().isInterrupted()) {
                            // Each frame in the FRC dashboard image protocol starts with a 4 magic numbers.  If we
                            // don't get those four numbers, something's wrong.
                            inputStream.readFully(magic);
                            if (!Arrays.equals(magic, MAGIC_NUMBERS)) {
                                throw new IOException("Invalid stream (wrong magic numbers)");
                            }

                            // Next, the server sends a 32-bit number indicating the number of bytes in this frame,
                            // then the raw bytes.
                            int imageSize = inputStream.readInt();
                            imageBuffer = growIfNecessary(imageBuffer, imageSize);
                            inputStream.read(imageBuffer, 0, imageSize);

                            // Decode the image and redraw
                            synchronized (imageLock) {
                                ImageIO.setUseCache(false);
                                image = ImageIO.read(new ByteArrayInputStream(imageBuffer, 0, imageSize));
                                repaint();
                            }
                        }
                    } catch (IOException e) {
                        logger.log(Level.WARNING, "Error in capture thread", e);

                        synchronized (imageLock) {
                            image = null;
                            error = e.getMessage();
                            repaint();
                        }
                    } finally {
                        // Wait a second before trying again
                        Thread.sleep(1000);
                    }
                } catch (InterruptedException e) {
                    // The main thread will interrupt the capture thread to indicate that properties have changed, or
                    // possible the thread should shut down.
                    logger.log(Level.INFO, "Capture thread interrupted");
                }
            }
        }, "Capture");

        thread.start();

    }

    /**
     * Return an array big enough to hold least at least "capacity" elements.  If the supplied buffer is big enough,
     * it will be reused to avoid unnecessary allocations.
     */
    private static byte[] growIfNecessary(byte[] buffer, int capacity) {
        if (capacity > buffer.length) {
            int newCapacity = buffer.length;
            while (newCapacity < capacity) {
                newCapacity *= 1.5;
            }
            logger.info("Growing to " + newCapacity);
            return new byte[newCapacity];
        }

        return buffer;
    }

    @Override
    public void disconnect() {
        shutdown = true;
        thread.interrupt();
    }

    @Override
    public void propertyChanged(Property property) {
        thread.interrupt();
    }

    @Override
    protected void paintComponent(Graphics g) {
        final int em = g.getFontMetrics().getHeight();

        synchronized (imageLock) {
            if (image == null) {
                g.setColor(Color.PINK);
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(Color.BLACK);
                g.drawString(error.isEmpty() ? "Unknown error" : error, em / 2, em);
            } else {
                g.drawImage(image, 0, 0, getWidth(), getHeight(), null);
            }
        }

        invalidate();
    }
}
