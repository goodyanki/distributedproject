package org.example;
import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Scanner;
import java.util.concurrent.ThreadLocalRandom;

public class Main {
    public static void main(String[] args) throws IOException {

        ByteBuffer buf = ByteBuffer.allocate(32);
        buf.order(ByteOrder.BIG_ENDIAN);

        System.out.println("Welcome!");
        System.out.println("-----------------------------");
        System.out.println("1. Check Availability");
        System.out.println("2. Make a Booking");
        System.out.println("3. Modify a Booking");
        System.out.println("4. Cancel a Booking");
        System.out.println("-----------------------------");

        Scanner scanner = new Scanner(System.in);
        System.out.print("Please enter your choice (1-4): ");
        int choice = scanner.nextInt();
        scanner.nextLine(); // 清除上一行的换行
        System.out.println("You selected option " + choice + ".");

        if (choice == 1) {
            // 选项1：查询可用性 -> facility id + booking time
            System.out.print("Enter facility id (integer): ");
            int facilityId = Integer.parseInt(scanner.nextLine());

            // 修改处：分开输入日期和时间
            System.out.print("Enter booking date (format: yyyymmdd): ");
            int bookingDate = scanner.nextInt();
            scanner.nextLine(); // 清除换行

            System.out.print("Enter booking time (format: xxxxxxxx): ");
            int bookingTime = scanner.nextInt();
            scanner.nextLine(); // 清除换行

            System.out.println("-----------------------------");
            System.out.println("Check Availability");
            System.out.println("Facility ID : " + facilityId);
            System.out.println("Booking Date: " + bookingDate);
            System.out.println("Booking Time: " + bookingTime);
            System.out.println("-----------------------------");
            System.out.println("Availability request recorded (not sent yet).");


            long requestId = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);  //生成requestID
            buf.putLong(requestId);
            long BookingId = 0L;  //生成bookingID
            buf.putLong(BookingId);
            buf.putInt(facilityId);
            buf.putInt(bookingDate);
            buf.putInt(bookingTime);
            buf.putInt('1'); //表示为查询

            DatagramSocket socket = new DatagramSocket();
            InetAddress serverAddr = InetAddress.getByName("127.0.0.1");  //目标地址
            int serverPort = 9000; //目标地址
            byte[] data = buf.array();
            DatagramPacket packet = new DatagramPacket(data, buf.position(), serverAddr, serverPort);
            socket.send(packet);
            socket.close();

        } else if (choice == 2) {
            // 选项2：预订 -> facility id + booking time
            System.out.print("Enter facility id (integer): ");
            int facilityId = Integer.parseInt(scanner.nextLine());

            // 修改处：分开输入日期和时间
            System.out.print("Enter booking date (format: yyyymmdd): ");
            int bookingDate = scanner.nextInt();
            scanner.nextLine(); // 清除换行

            System.out.print("Enter booking time (format: xxxxxxxx): ");
            int bookingTime = scanner.nextInt();
            scanner.nextLine(); // 清除换行

            System.out.println("-----------------------------");
            System.out.println("Make a Booking");
            System.out.println("Facility ID : " + facilityId);
            System.out.println("Booking Date: " + bookingDate);
            System.out.println("Booking Time: " + bookingTime);
            System.out.println("-----------------------------");
            System.out.println("Booking request recorded (not sent yet).");

            long requestId = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);  //生成requestID
            buf.putLong(requestId);
            long BookingId = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);  //生成bookingID
            buf.putLong(BookingId);
            buf.putInt(facilityId);
            buf.putInt(bookingDate);
            buf.putInt(bookingTime);
            buf.putInt(2); //表示为新增

            DatagramSocket socket = new DatagramSocket();
            InetAddress serverAddr = InetAddress.getByName("127.0.0.1");  //目标地址
            int serverPort = 9000; //目标地址
            byte[] data = buf.array();
            DatagramPacket packet = new DatagramPacket(data, buf.position(), serverAddr, serverPort);
            socket.send(packet);
            socket.close();







        } else if (choice == 3) {
            // 选项3：修改预订 -> booking id + facility id + booking time
            System.out.print("Enter booking id (integer): ");
            String bookingId = scanner.nextLine();

            System.out.print("Enter facility id (integer): ");
            String facilityId = scanner.nextLine();

            // 修改处：分开输入日期和时间
            System.out.print("Enter new booking date (format: yyyymmdd): ");
            int bookingDate = scanner.nextInt();
            scanner.nextLine(); // 清除换行

            System.out.print("Enter new booking time (format: xxxxxxxx): ");
            int bookingTime = scanner.nextInt();
            scanner.nextLine(); // 清除换行

            System.out.println("-----------------------------");
            System.out.println("Modify a Booking");
            System.out.println("Booking ID  : " + bookingId);
            System.out.println("Facility ID : " + facilityId);
            System.out.println("Booking Date: " + bookingDate);
            System.out.println("Booking Time: " + bookingTime);
            System.out.println("-----------------------------");
            System.out.println("Modify request recorded (not sent yet).");

        } else if (choice == 4) {
            // 选项4：取消预订 -> booking id + 确认
            System.out.print("Enter booking id (integer): ");
            String bookingId = scanner.nextLine();

            System.out.print("Are you sure to cancel this booking? (y/n): ");
            String confirm = scanner.nextLine();

            System.out.println("-----------------------------");
            if (confirm.equals("y") || confirm.equals("Y")) {
                System.out.println("Cancel a Booking");
                System.out.println("Booking ID  : " + bookingId);
                System.out.println("Action      : deleted");
                System.out.println("-----------------------------");
                System.out.println("Cancel request recorded (not sent yet).");
            } else {
                System.out.println("Cancel a Booking");
                System.out.println("Booking ID  : " + bookingId);
                System.out.println("Action      : NOT deleted");
                System.out.println("-----------------------------");
                System.out.println("Operation aborted by user.");
            }

        } else {
            System.out.println("Function not implemented yet.");
        }

        scanner.close();
    }
}
