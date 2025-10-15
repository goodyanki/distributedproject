package org.example;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
import java.util.concurrent.ThreadLocalRandom;

public class Client {
    private static final int MAX_RETRIES = 5;   // 最大重试次数
    private static final int TIMEOUT_MS = 1000; // 超时时间
    private static final String SERVER_HOST = "127.0.0.1";
    private static final int SERVER_PORT = 9876;

    @FunctionalInterface
    interface ResponseHandler {
        void handle(DatagramPacket recvPacket) throws Exception;
    }

    public static void main(String[] args) throws Exception {
        Scanner scanner = new Scanner(System.in);

        System.out.println("╔════════════════════════════════════════════╗");
        System.out.println("║   Facility Booking System - Client         ║");
        System.out.println("╚════════════════════════════════════════════╝");
        System.out.println();

        while (true) {
            System.out.println("┌────────────────────────────────────────────┐");
            System.out.println("│              MAIN MENU                     │");
            System.out.println("├────────────────────────────────────────────┤");
            System.out.println("│  1. Query Availability                     │");
            System.out.println("│  2. Make a Booking                         │");
            System.out.println("│  3. Modify a Booking                       │");
            System.out.println("│  4. Exit                                   │");
            System.out.println("│  5. OP_A (Idempotent Operation)            │");
            System.out.println("│  6. OP_B (Non-Idempotent Operation)        │");
            System.out.println("└────────────────────────────────────────────┘");
            System.out.println();

            System.out.print("Please enter your choice (1-6): ");
            int choice = scanner.nextInt();
            scanner.nextLine();
            System.out.println();

            if (choice == 1) {
                handleQuery(scanner);
            } else if (choice == 2) {
                handleBooking(scanner);
            } else if (choice == 3) {
                handleModify(scanner);
            } else if (choice == 4) {
                System.out.println("✓ Thank you for using Facility Booking System!");
                System.out.println("Goodbye!");
                scanner.close();
                return;
            } else if (choice == 5) {
                handleOpA(scanner);
            } else if (choice == 6) {
                handleOpB(scanner);
            } else {
                System.out.println("✗ Invalid choice! Please select 1-6.");
            }

            System.out.println();
            System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            System.out.println();
        }
    }

    private static void sendWithRetry(byte[] data, int requestId, String opName, ResponseHandler handler) throws IOException {
        DatagramSocket socket = new DatagramSocket();
        InetAddress serverAddr = InetAddress.getByName(SERVER_HOST);
        socket.setSoTimeout(TIMEOUT_MS);

        boolean success = false;
        int attempt = 0;

        while (attempt <= MAX_RETRIES && !success) {
            attempt++;
            try {
                DatagramPacket sendPacket = new DatagramPacket(data, data.length, serverAddr, SERVER_PORT);
                socket.send(sendPacket);

                System.out.println(opName + " request sent! (Request ID: " + requestId +
                        ", Attempt: " + attempt + "/" + (MAX_RETRIES + 1) + ")");

                // 等待响应
                byte[] recvBuf = new byte[2048];
                DatagramPacket recvPacket = new DatagramPacket(recvBuf, recvBuf.length);
                socket.receive(recvPacket);

                if (handler != null) {
                    try {
                        handler.handle(recvPacket);
                    } catch (Exception parseEx) {
                        System.out.println("✗ Failed to parse response: " + parseEx.getMessage());
                    }
                } else {
                    System.out.println("✓ Got response from server!");
                }

                System.out.println("✓ Operation completed successfully.");
                success = true;
            } catch (SocketTimeoutException e) {
                if (attempt <= MAX_RETRIES) {
                    System.out.println("Timeout waiting for reply... Retrying... (" +
                            attempt + "/" + (MAX_RETRIES + 1) + ")");
                } else {
                    System.out.println("✗ No response after " + (MAX_RETRIES + 1) +
                            " attempts. Operation may or may not have succeeded.");
                    System.out.println(" Use 'Query' to verify the actual state.");
                }
            }
        }

        socket.close();

        if (!success) {
            System.out.println();
            System.out.println("⚠ IMPORTANT: If using AT_LEAST_ONCE semantic,");
            System.out.println("   the operation may have been executed MULTIPLE times!");
            System.out.println("   Check with a Query to see actual bookings.");
        }
    }

    private static void handleOpA(Scanner scanner) throws IOException {
        System.out.println("┌─ OP_A (Idempotent Operation)─────────────────┐");
        System.out.println();

        System.out.print("Enter facility name (e.g., RoomA): ");
        String facilityName = scanner.nextLine();

        System.out.println();
        System.out.println("✓ Executing OP_A on " + facilityName + "...");
        System.out.println();

        byte[] nameBytes = facilityName.getBytes(StandardCharsets.UTF_8);
        int payloadLen = 2 + nameBytes.length;
        int totalLen = 10 + payloadLen;

        ByteBuffer buf = ByteBuffer.allocate(totalLen);

        int requestId = ThreadLocalRandom.current().nextInt(Integer.MAX_VALUE);
        buf.putInt(requestId);
        buf.put((byte) 5);  // OP_A
        buf.put((byte) 0);
        buf.putInt(payloadLen);

        buf.putShort((short) nameBytes.length);
        buf.put(nameBytes);

        byte[] data = buf.array();

        sendWithRetry(data, requestId, "OP_A", recvPacket -> {
            System.out.println("✓ Got response from server!");
        });
    }

    private static void handleOpB(Scanner scanner) throws IOException {
        System.out.println("┌─ OP_B (Non-Idempotent Operation)─────────────┐");
        System.out.println();

        System.out.print("Enter facility name (e.g., RoomA): ");
        String facilityName = scanner.nextLine();

        System.out.println();
        System.out.println("⚠ Executing OP_B on " + facilityName + "...");
        System.out.println("⚠ (This will create a 1-minute booking)");
        System.out.println();

        byte[] nameBytes = facilityName.getBytes(StandardCharsets.UTF_8);
        int payloadLen = 2 + nameBytes.length;
        int totalLen = 10 + payloadLen;

        ByteBuffer buf = ByteBuffer.allocate(totalLen);

        int requestId = ThreadLocalRandom.current().nextInt(Integer.MAX_VALUE);
        buf.putInt(requestId);
        buf.put((byte) 6);  // OP_B
        buf.put((byte) 0);
        buf.putInt(payloadLen);

        buf.putShort((short) nameBytes.length);
        buf.put(nameBytes);

        byte[] data = buf.array();

        sendWithRetry(data, requestId, "OP_B", recvPacket -> {
            System.out.println("✓ Got response from server!");
        });
    }

    // ───────────────────────────── Query / Booking / Modify 接入重试 ─────────────────────────────
    private static void handleQuery(Scanner scanner) throws Exception {
        System.out.println("┌─ Check Availability Here─────────────────────┐");
        System.out.println();

        System.out.print("Enter facility name (e.g., RoomA): ");
        String facilityName = scanner.nextLine();

        System.out.print("Enter day to check (0=Mon, 1=Tue, ..., 6=Sun): ");
        int day = scanner.nextInt();
        scanner.nextLine();

        System.out.println();
        System.out.println(" Checking " + facilityName + " for day " + day + "...");
        System.out.println();

        byte[] nameBytes = facilityName.getBytes(StandardCharsets.UTF_8);
        int payloadLen = 2 + nameBytes.length + 2;
        int totalLen = 10 + payloadLen;

        ByteBuffer buf = ByteBuffer.allocate(totalLen);

        int requestId = ThreadLocalRandom.current().nextInt(Integer.MAX_VALUE);
        buf.putInt(requestId);
        buf.put((byte) 1);  // QUERY
        buf.put((byte) 0);
        buf.putInt(payloadLen);

        buf.putShort((short) nameBytes.length);
        buf.put(nameBytes);

        buf.put((byte) 1);
        buf.put((byte) day);

        byte[] data = buf.array();

        sendWithRetry(data, requestId, "QUERY", recvPacket -> {
            System.out.println("✓ Got response from server!");

            ByteBuffer resp = ByteBuffer.wrap(recvPacket.getData(), 0, recvPacket.getLength());
            int respId = resp.getInt();
            int respCode = resp.get() & 0xFF;
            int respPayloadLen = resp.getInt();

            if (respCode == 0 && respPayloadLen > 0) {
                System.out.println();
                System.out.println(" Query Results:");
                int dayCount = resp.getShort() & 0xFFFF;

                for (int i = 0; i < dayCount; i++) {
                    int dayIndex = resp.get() & 0xFF;
                    int intervalCount = resp.getShort() & 0xFFFF;

                    String[] dayNames = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
                    String dayName = dayNames[dayIndex];
                    System.out.println("   Day " + dayIndex + " (" + dayName + "):");

                    if (intervalCount == 0) {
                        System.out.println("     ✓ No bookings - Available all day!");
                    } else {
                        System.out.println("      " + intervalCount + " booking(s):");
                        for (int j = 0; j < intervalCount; j++) {
                            int start = resp.getShort() & 0xFFFF;
                            int end = resp.getShort() & 0xFFFF;
                            System.out.printf("        - %02d:%02d - %02d:%02d%n",
                                    start / 60, start % 60, end / 60, end % 60);
                        }
                    }
                }
            } else if (respCode != 0) {
                System.out.println("✗ Query failed! Error code: " + respCode);
            } else {
                System.out.println("✗ Empty payload.");
            }
        });
    }

    private static void handleBooking(Scanner scanner) throws Exception {
        System.out.println("┌─ Make a Booking Here─────────────────────────┐");
        System.out.println();

        System.out.print("Enter facility name (e.g., RoomA): ");
        String facilityName = scanner.nextLine();

        System.out.println();
        System.out.println("Start Time:");
        System.out.print("  Day (0-6): ");
        int startDay = scanner.nextInt();
        System.out.print("  Hour (0-23): ");
        int startHour = scanner.nextInt();
        System.out.print("  Minute (0-59): ");
        int startMin = scanner.nextInt();

        System.out.println();
        System.out.println("End Time:");
        System.out.print("  Day (0-6): ");
        int endDay = scanner.nextInt();
        System.out.print("  Hour (0-23): ");
        int endHour = scanner.nextInt();
        System.out.print("  Minute (0-59): ");
        int endMin = scanner.nextInt();
        scanner.nextLine();

        System.out.println();
        System.out.println(" Booking Summary:");
        System.out.println("   Facility: " + facilityName);
        System.out.printf("   Start: Day %d, %02d:%02d%n", startDay, startHour, startMin);
        System.out.printf("   End: Day %d, %02d:%02d%n", endDay, endHour, endMin);
        System.out.println();

        byte[] nameBytes = facilityName.getBytes(StandardCharsets.UTF_8);
        int payloadLen = 2 + nameBytes.length + 6;
        int totalLen = 10 + payloadLen;

        ByteBuffer buf = ByteBuffer.allocate(totalLen);

        int requestId = ThreadLocalRandom.current().nextInt(Integer.MAX_VALUE);
        buf.putInt(requestId);
        buf.put((byte) 2);  // BOOK
        buf.put((byte) 0);
        buf.putInt(payloadLen);

        buf.putShort((short) nameBytes.length);
        buf.put(nameBytes);

        buf.put((byte) startDay);
        buf.put((byte) startHour);
        buf.put((byte) startMin);

        buf.put((byte) endDay);
        buf.put((byte) endHour);
        buf.put((byte) endMin);

        byte[] data = buf.array();

        sendWithRetry(data, requestId, "BOOK", recvPacket -> {
            System.out.println("✓ Got response from server!");
            ByteBuffer resp = ByteBuffer.wrap(recvPacket.getData(), 0, recvPacket.getLength());
            int respId = resp.getInt();
            int respCode = resp.get() & 0xFF;
            int respPayloadLen = resp.getInt();

            if (respCode == 0) {
                System.out.println("✓ Booking confirmed!");
                if (respPayloadLen >= 4) {
                    int confirmationId = resp.getInt();
                    System.out.println("   Confirmation ID: " + confirmationId);
                }
            } else {
                System.out.println("✗ Booking failed! Error code: " + respCode);
            }
        });
    }

    private static void handleModify(Scanner scanner) throws Exception {
        System.out.println("┌─ Modify a Booking Here───────────────────────┐");
        System.out.println();

        System.out.print("Enter confirmation ID: ");
        int confirmationId = scanner.nextInt();

        System.out.print("Enter offset in minutes (+ to postpone, - to advance): ");
        int offsetMinutes = scanner.nextInt();
        scanner.nextLine();

        System.out.println();
        System.out.println(" Modifying booking #" + confirmationId + "...");
        System.out.println("   Offset: " + offsetMinutes + " minutes");
        System.out.println();

        int payloadLen = 8;
        int totalLen = 10 + payloadLen;

        ByteBuffer buf = ByteBuffer.allocate(totalLen);

        int requestId = ThreadLocalRandom.current().nextInt(Integer.MAX_VALUE);
        buf.putInt(requestId);
        buf.put((byte) 3);  // CHANGE
        buf.put((byte) 0);
        buf.putInt(payloadLen);

        buf.putInt(confirmationId);
        buf.putInt(offsetMinutes);

        byte[] data = buf.array();

        sendWithRetry(data, requestId, "CHANGE", recvPacket -> {
            System.out.println("✓ Got response from server!");
            ByteBuffer resp = ByteBuffer.wrap(recvPacket.getData(), 0, recvPacket.getLength());
            int respId = resp.getInt();
            int respCode = resp.get() & 0xFF;
            int respPayloadLen = resp.getInt();

            if (respCode == 0) {
                System.out.println("✓ Booking modified successfully!");
            } else {
                System.out.println("✗ Modify failed! Error code: " + respCode);
            }
        });
    }
}
