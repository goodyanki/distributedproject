package org.example;

import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
import java.util.concurrent.ThreadLocalRandom;

public class Client {
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
            System.out.println("│  6. OP_B (Non-Idempotent Operation)            │");
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
                System.out.println(" Thank you for using Facility Booking System!");
                System.out.println("Goodbye!");
                scanner.close();
                return;
            } else if (choice == 5){
                handleOpA(scanner);
            }else if (choice == 6){
                handleOpB(scanner);
            }
                else {
                System.out.println(" Invalid choice! Please select 1-4.");
            }

            System.out.println();
            System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            System.out.println();
        }
    }

    private static void handleOpB(Scanner scanner) throws IOException {
        System.out.println("┌─ OP_B (Non-Idempotent Operation)─────────────┐");
        System.out.println();

        System.out.print("Enter facility name (e.g., RoomA): ");
        String facilityName = scanner.nextLine();

        System.out.println();
        System.out.println(" Executing OP_B on " + facilityName + "...");
        System.out.println(" (This will create a 1-minute booking)");
        System.out.println();

        byte[] nameBytes = facilityName.getBytes(StandardCharsets.UTF_8);
        int payloadLen = 2 + nameBytes.length;
        int totalLen = 10 + payloadLen;

        ByteBuffer buf = ByteBuffer.allocate(totalLen);

        int requestId = ThreadLocalRandom.current().nextInt(Integer.MAX_VALUE);
        buf.putInt(requestId);
        buf.put((byte) 6);
        buf.put((byte) 0);
        buf.putInt(payloadLen);

        buf.putShort((short) nameBytes.length);
        buf.put(nameBytes);

        DatagramSocket socket = new DatagramSocket();
        InetAddress serverAddr = InetAddress.getByName("127.0.0.1");
        int serverPort = 9876;
        byte[] data = buf.array();
        DatagramPacket packet = new DatagramPacket(
                data,
                buf.position(),
                serverAddr,
                serverPort
        );

        socket.send(packet);
        System.out.println(" OP_B request sent! (Request ID: " + requestId + ")");

        socket.setSoTimeout(3000);
        byte[] recvBuf = new byte[1024];
        DatagramPacket recvPacket = new DatagramPacket(recvBuf, recvBuf.length);

        socket.receive(recvPacket);
        System.out.println(" Got response from server!");
        System.out.println(" (A new 1-minute booking was created)");

        socket.close();
    }

    private static void handleOpA(Scanner scanner) throws IOException {
        System.out.println("┌─ OP_A (Idempotent Operation)─────────────────┐");
        System.out.println();

        System.out.print("Enter facility name (e.g., RoomA): ");
        String facilityName = scanner.nextLine();

        System.out.println();
        System.out.println(" Executing OP_A on " + facilityName + "...");
        System.out.println();

        byte[] nameBytes = facilityName.getBytes(StandardCharsets.UTF_8);
        int payloadLen = 2 + nameBytes.length;
        int totalLen = 10 + payloadLen;

        ByteBuffer buf = ByteBuffer.allocate(totalLen);

        int requestId = ThreadLocalRandom.current().nextInt(Integer.MAX_VALUE);
        buf.putInt(requestId);
        buf.put((byte) 5);
        buf.put((byte) 0);
        buf.putInt(payloadLen);

        buf.putShort((short) nameBytes.length);
        buf.put(nameBytes);

        DatagramSocket socket = new DatagramSocket();
        InetAddress serverAddr = InetAddress.getByName("127.0.0.1");
        int serverPort = 9876;
        byte[] data = buf.array();
        DatagramPacket packet = new DatagramPacket(
                data,
                buf.position(),
                serverAddr,
                serverPort
        );

        socket.send(packet);
        System.out.println(" OP_A request sent! (Request ID: " + requestId + ")");

        socket.setSoTimeout(3000);
        byte[] recvBuf = new byte[1024];
        DatagramPacket recvPacket = new DatagramPacket(recvBuf, recvBuf.length);

        socket.receive(recvPacket);
        System.out.println(" Got response from server!");

        socket.close();
    }

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
        buf.put((byte) 1);
        buf.put((byte) 0);
        buf.putInt(payloadLen);

        buf.putShort((short) nameBytes.length);
        buf.put(nameBytes);

        buf.put((byte) 1);
        buf.put((byte) day);

        DatagramSocket socket = new DatagramSocket();
        InetAddress serverAddr = InetAddress.getByName("127.0.0.1");
        int serverPort = 9876;
        byte[] data = buf.array();
        DatagramPacket packet = new DatagramPacket(
                data,
                buf.position(),
                serverAddr,
                serverPort
        );

        socket.send(packet);
        System.out.println(" Query request sent! (Request ID: " + requestId + ")");

        socket.setSoTimeout(3000);
        byte[] recvBuf = new byte[1024];
        DatagramPacket recvPacket = new DatagramPacket(recvBuf, recvBuf.length);

        socket.receive(recvPacket);
        System.out.println(" Got response from server!");

        socket.close();
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
        System.out.printf("   Start: Day %d, %02d:%02d\n", startDay, startHour, startMin);
        System.out.printf("   End: Day %d, %02d:%02d\n", endDay, endHour, endMin);
        System.out.println();

        byte[] nameBytes = facilityName.getBytes(StandardCharsets.UTF_8);
        int payloadLen = 2 + nameBytes.length + 6;
        int totalLen = 10 + payloadLen;

        ByteBuffer buf = ByteBuffer.allocate(totalLen);

        int requestId = ThreadLocalRandom.current().nextInt(Integer.MAX_VALUE);
        buf.putInt(requestId);
        buf.put((byte) 2);
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

        DatagramSocket socket = new DatagramSocket();
        InetAddress serverAddr = InetAddress.getByName("127.0.0.1");
        int serverPort = 9876;
        byte[] data = buf.array();
        DatagramPacket packet = new DatagramPacket(
                data,
                buf.position(),
                serverAddr,
                serverPort
        );

        socket.send(packet);
        System.out.println(" Booking request sent! (Request ID: " + requestId + ")");

        socket.setSoTimeout(3000);
        byte[] recvBuf = new byte[1024];
        DatagramPacket recvPacket = new DatagramPacket(recvBuf, recvBuf.length);

        socket.receive(recvPacket);
        System.out.println(" Got response from server!");

        socket.close();
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
        System.out.println("  Modifying booking #" + confirmationId + "...");
        System.out.println("   Offset: " + offsetMinutes + " minutes");
        System.out.println();

        int payloadLen = 8;
        int totalLen = 10 + payloadLen;

        ByteBuffer buf = ByteBuffer.allocate(totalLen);

        int requestId = ThreadLocalRandom.current().nextInt(Integer.MAX_VALUE);
        buf.putInt(requestId);
        buf.put((byte) 3);
        buf.put((byte) 0);
        buf.putInt(payloadLen);

        buf.putInt(confirmationId);
        buf.putInt(offsetMinutes);

        DatagramSocket socket = new DatagramSocket();
        InetAddress serverAddr = InetAddress.getByName("127.0.0.1");
        int serverPort = 9876;
        byte[] data = buf.array();
        DatagramPacket packet = new DatagramPacket(
                data,
                buf.position(),
                serverAddr,
                serverPort
        );

        socket.send(packet);
        System.out.println(" Modify request sent! (Request ID: " + requestId + ")");

        socket.setSoTimeout(3000);
        byte[] recvBuf = new byte[1024];
        DatagramPacket recvPacket = new DatagramPacket(recvBuf, recvBuf.length);

        socket.receive(recvPacket);
        System.out.println(" Got response from server!");

        socket.close();
    }
}