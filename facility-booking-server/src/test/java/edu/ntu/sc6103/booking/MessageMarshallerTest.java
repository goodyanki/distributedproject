package edu.ntu.sc6103.booking;

import edu.ntu.sc6103.booking.protocol.MessageMarshaller;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Arrays; // <-- 新增: 引入 Arrays
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class MessageMarshallerTest {

    @Test
    public void testRequestMarshalUnmarshalQuery() {
        int reqId = 12345;

        // byte[] payload = MessageMarshaller.buildQueryPayload("RoomA", List.of(0,1,2)); // Java 9+
        byte[] payload = MessageMarshaller.buildQueryPayload("RoomA", Arrays.asList(0,1,2)); // Java 8 兼容

        MessageMarshaller.Request req = new MessageMarshaller.Request(reqId, MessageMarshaller.OperationType.QUERY, MessageMarshaller.InvocationSemantic.DEFAULT, payload);
        byte[] raw = MessageMarshaller.marshalRequest(req);

        MessageMarshaller.Request u = MessageMarshaller.unmarshalRequest(raw, raw.length);
        assertEquals(reqId, u.requestId);
        assertEquals(MessageMarshaller.OperationType.QUERY, u.op);

        MessageMarshaller.QueryPayload qp = MessageMarshaller.parseQueryPayload(u.payload);
        assertEquals("RoomA", qp.facilityName);
        assertEquals(3, qp.days.size());
    }

    @Test
    public void testResponseMarshalUnmarshal() {
        int reqId = 999;
        byte[] data = new byte[]{0,1,2,3};
        MessageMarshaller.Response rsp = new MessageMarshaller.Response(reqId, MessageMarshaller.ResponseCode.OK, data);
        byte[] raw = MessageMarshaller.marshalResponse(rsp);

        MessageMarshaller.Response ur = MessageMarshaller.unmarshalResponse(raw, raw.length);
        assertEquals(reqId, ur.requestId);
        assertEquals(MessageMarshaller.ResponseCode.OK, ur.code);
        assertArrayEquals(data, ur.payload);
    }
}