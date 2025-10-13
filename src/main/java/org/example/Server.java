package org.example;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.*;

public class Server {
    public static void main(String[] args) throws  Exception {
        Connection conn = DriverManager.getConnection("jdbc:sqlite:server.db");

        try (DatagramSocket socket = new DatagramSocket(9000)) {
            System.out.println(" Server is listening on UDP port 9000...");

            byte[] buffer = new byte[1024];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);

            while (true) {
                socket.receive(packet);
                System.out.println(" Received packet from " + packet.getAddress() + ":" + packet.getPort());

                byte[] data = packet.getData();
                ByteBuffer buf = ByteBuffer.wrap(data);
                buf.order(ByteOrder.BIG_ENDIAN);  //收到文件

                long requestId = buf.getLong();
                long bookingId = buf.getLong();
                int facilityId = buf.getInt();
                int bookingDate = buf.getInt();
                int bookingTime = buf.getInt();
                int opCode = buf.getInt();

                if (opCode == 2 )
                {
                    String sql = "INSERT INTO booking (" +
                            "request_id, " +
                            "booking_id, " +
                            "facility_id, " +
                            "booking_date, " +
                            "bo oking_time, " +
                            "op_code) " +
                            "VALUES (?, ?, ?, ?, ?, ?)";
                    PreparedStatement ps = conn.prepareStatement(sql);

                    ps.setLong(1, requestId);   // 第1个 ? → request_id
                    ps.setLong(2, bookingId);   // 第2个 ? → booking_id
                    ps.setInt(3, facilityId);// 第3个 ? → facility_id
                    ps.setInt(4, bookingDate);  // 第4个 ? → booking_date
                    ps.setInt(5, bookingTime);  // 第5个 ? → booking_time
                    ps.setInt(6, opCode);       // 第6个 ? → op_code


                }






            }
        }


    }
}
