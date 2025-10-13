package org.example;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class TestServer {
    public static void main(String[] args) throws Exception {
        DatagramSocket socket = new DatagramSocket(9000); // 监听 9000 端口
        System.out.println("Server started, listening on port 9000...");

        byte[] buffer = new byte[1024];

        while (true) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            socket.receive(packet);

            System.out.println("Received packet from " + packet.getAddress() + ":" + packet.getPort());
            byte[] data = packet.getData();

            // 解析字节序列
            ByteBuffer buf = ByteBuffer.wrap(data);
            buf.order(ByteOrder.BIG_ENDIAN);

            long requestId = buf.getLong();
            long bookingId = buf.getLong();
            int facilityId = buf.getInt();
            int bookingDate = buf.getInt();
            int bookingTime = buf.getInt();
            int opCode = buf.getInt();

            System.out.println("----Decoded Message----");
            System.out.println("Request ID : " + requestId);
            System.out.println("Booking ID : " + bookingId);
            System.out.println("Facility ID: " + facilityId);
            System.out.println("Booking Date: " + bookingDate);
            System.out.println("Booking Time: " + bookingTime);
            System.out.println("OpCode: " + (char)opCode);
            System.out.println("------------------------");


        }
    }
}
