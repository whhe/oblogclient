/*
 * Copyright 2024 OceanBase.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.oceanbase.clogproxy.client.connection;

import com.oceanbase.clogproxy.client.config.ClientConf;
import com.oceanbase.clogproxy.client.enums.ErrorCode;
import com.oceanbase.clogproxy.client.exception.LogProxyClientException;
import com.oceanbase.clogproxy.client.util.ClientUtil;
import com.oceanbase.clogproxy.common.packet.CompressType;
import com.oceanbase.clogproxy.common.packet.HeaderType;
import com.oceanbase.clogproxy.common.packet.ProtocolVersion;
import com.oceanbase.clogproxy.common.packet.protocol.LogProxyProto;
import com.oceanbase.clogproxy.common.util.NetworkUtil;
import com.oceanbase.oms.logmessage.LogMessage;

import com.google.protobuf.InvalidProtocolBufferException;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.ByteToMessageDecoder.Cumulator;
import io.netty.handler.timeout.IdleStateEvent;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;
import org.apache.commons.lang3.Conversion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.BlockingQueue;

/** This is an implementation class of {@link ChannelInboundHandlerAdapter}. */
public class ClientHandler extends ChannelInboundHandlerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(ClientHandler.class);

    /** Magic string used to request log proxy. */
    private static final byte[] MAGIC_STRING = new byte[] {'x', 'i', '5', '3', 'g', ']', 'q'};

    /** Client ip address. */
    private static final String CLIENT_IP = NetworkUtil.getLocalIp();

    /** Length of packet header. */
    private static final int HEAD_LENGTH = 7;

    /** A client stream. */
    private ClientStream stream;

    /** Client config. */
    private ClientConf config;

    /** Connection params. */
    private ConnectionParams params;

    /**
     * Record queue, it's a {@link BlockingQueue} for storing {@link StreamContext.TransferPacket}.
     */
    private BlockingQueue<StreamContext.TransferPacket> recordQueue;

    /** Handshake type enumeration. */
    enum HandshakeState {
        /** State of parsing the packet header. */
        PB_HEAD,
        /** State of handling handshake response. */
        CLIENT_HANDSHAKE_RESPONSE,
        /** State of handling record. */
        RECORD,
        /** State of handling error response. */
        ERROR_RESPONSE,
        /** State of handling runtime status response. */
        STATUS
    }

    /** Handshake state. */
    private HandshakeState state = HandshakeState.PB_HEAD;

    /** A {@link Cumulator} instance. */
    private final Cumulator cumulator = ByteToMessageDecoder.MERGE_CUMULATOR;

    /** A {@link ByteBuf} used for channel reading. */
    ByteBuf buffer;

    /** A flag of whether channel is active. */
    private boolean poolFlag = true;

    /** A flag of whether it is the first part of {@link ByteBuf}. */
    private boolean first;

    /** Number of read attempts. */
    private int numReads = 0;

    /** A flag of whether the message is not readable. */
    private boolean dataNotEnough = false;

    /** The length of message body. */
    private int dataLength = 0;

    /** A {@link LZ4Factory} instance. */
    LZ4Factory factory = LZ4Factory.fastestInstance();

    /** A {@link LZ4FastDecompressor} instance. */
    LZ4FastDecompressor fastDecompressor = factory.fastDecompressor();

    ClientHandlerV01 clientHandlerV01;

    /** Constructor with empty arguments. */
    public ClientHandler() {}

    /** Reset {@link #state} to {@link HandshakeState#PB_HEAD}. */
    protected void resetState() {
        if (params.getProtocolVersion().code() < ProtocolVersion.V2.code()) {
            clientHandlerV01.resetState();
        } else {
            state = HandshakeState.PB_HEAD;
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof ByteBuf) {
            dataNotEnough = false;
            ByteBuf data = (ByteBuf) msg;
            first = buffer == null;
            if (first) {
                buffer = data;
            } else {
                buffer = cumulator.cumulate(ctx.alloc(), buffer, data);
            }
        } else if (msg instanceof IdleStateEvent) {
            if (stream != null) {
                stream.triggerReconnect();
            }
            return;
        } else {
            return;
        }

        while (poolFlag && buffer.isReadable() && !dataNotEnough) {
            if (params.getProtocolVersion().code() < ProtocolVersion.V2.code()) {
                dataNotEnough = clientHandlerV01.channelRead(poolFlag, buffer, dataNotEnough);
                continue;
            }

            switch (state) {
                case PB_HEAD:
                    handleHeader();
                    break;
                case CLIENT_HANDSHAKE_RESPONSE:
                    handleHandshakeResponse();
                    break;
                case ERROR_RESPONSE:
                    handleErrorResponse();
                    break;
                case STATUS:
                    handleServerStatus();
                    break;
                case RECORD:
                    handleRecord();
                    break;
            }
        }

        if (buffer != null && !buffer.isReadable()) {
            numReads = 0;
            buffer.release();
            buffer = null;
        } else if (++numReads >= config.getNettyDiscardAfterReads()) {
            numReads = 0;
            discardSomeReadBytes();
        }
    }

    /** Handle header response. */
    private void handleHeader() {
        if (buffer.readableBytes() >= HEAD_LENGTH) {
            int version = buffer.readShort();
            int type = buffer.readByte();
            dataLength = buffer.readInt();
            checkHeader(version, type, dataLength);

            HeaderType headerType = HeaderType.codeOf(type);
            if (headerType == HeaderType.HANDSHAKE_RESPONSE_CLIENT) {
                state = HandshakeState.CLIENT_HANDSHAKE_RESPONSE;
            } else if (headerType == HeaderType.ERROR_RESPONSE) {
                state = HandshakeState.ERROR_RESPONSE;
            } else if (headerType == HeaderType.DATA_CLIENT) {
                state = HandshakeState.RECORD;
            } else if (headerType == HeaderType.STATUS) {
                state = HandshakeState.STATUS;
            }
        } else {
            dataNotEnough = true;
        }
    }

    /** Handle handshake response. */
    private void handleHandshakeResponse() throws InvalidProtocolBufferException {
        if (buffer.readableBytes() >= dataLength) {
            byte[] bytes = new byte[dataLength];
            buffer.readBytes(bytes);
            LogProxyProto.ClientHandshakeResponse response =
                    LogProxyProto.ClientHandshakeResponse.parseFrom(bytes);
            logger.info(
                    "Connected to LogProxyServer, ip:{}, version:{}",
                    response.getIp(),
                    response.getVersion());
            state = HandshakeState.PB_HEAD;
        } else {
            dataNotEnough = true;
        }
    }

    /** Handle error response. */
    private void handleErrorResponse() throws InvalidProtocolBufferException {
        if (buffer.readableBytes() >= dataLength) {
            byte[] bytes = new byte[dataLength];
            buffer.readBytes(bytes);
            LogProxyProto.ErrorResponse response = LogProxyProto.ErrorResponse.parseFrom(bytes);
            logger.error("LogProxy refused handshake request: {}", response.toString());
            throw new LogProxyClientException(
                    ErrorCode.NO_AUTH,
                    "LogProxy refused handshake request: " + response.toString(),
                    true);
        } else {
            dataNotEnough = true;
        }
    }

    /** Handle server status response. */
    private void handleServerStatus() throws InvalidProtocolBufferException {
        if (buffer.readableBytes() >= dataLength) {
            byte[] bytes = new byte[dataLength];
            buffer.readBytes(bytes);
            LogProxyProto.RuntimeStatus response = LogProxyProto.RuntimeStatus.parseFrom(bytes);
            logger.debug("Server status: {}", response.toString());
            state = HandshakeState.PB_HEAD;
        } else {
            dataNotEnough = true;
        }
    }

    /** Handle record data response. */
    private void handleRecord() {
        if (buffer.readableBytes() >= dataLength) {
            parseData();
            state = HandshakeState.PB_HEAD;
        } else {
            dataNotEnough = true;
        }
    }

    /**
     * Check if the header is valid.
     *
     * @param version Protocol version.
     * @param type Header type.
     * @param length Data length.
     */
    private void checkHeader(int version, int type, int length) {
        if (ProtocolVersion.codeOf(version) == null && version != ProtocolVersion.V2.code()) {
            logger.error("Unsupported protocol version: {}", version);
            throw new LogProxyClientException(
                    ErrorCode.E_PROTOCOL, "Unsupported protocol version: " + version);
        }
        if (HeaderType.codeOf(type) == null) {
            logger.error("Unsupported header type: {}", type);
            throw new LogProxyClientException(
                    ErrorCode.E_HEADER_TYPE, "Unsupported header type: " + type);
        }
        if (length <= 0) {
            logger.error("Data length equals 0");
            throw new LogProxyClientException(ErrorCode.E_LEN, "Data length equals 0");
        }
    }

    /** Do parse record data from buffer. It will firstly decompress the raw data if necessary. */
    private void parseData() {
        try {
            byte[] buff = new byte[dataLength];
            buffer.readBytes(buff, 0, dataLength);
            LogProxyProto.RecordData recordData = LogProxyProto.RecordData.parseFrom(buff);
            int compressType = recordData.getCompressType();
            int compressedLen = recordData.getCompressedLen();
            int rawLen = recordData.getRawLen();
            byte[] rawData = recordData.getRecords().toByteArray();
            if (compressType == CompressType.LZ4.code()) {
                byte[] bytes = new byte[rawLen];
                int decompress = fastDecompressor.decompress(rawData, 0, bytes, 0, rawLen);
                if (decompress != compressedLen) {
                    throw new LogProxyClientException(
                            ErrorCode.E_LEN,
                            "decompressed length ["
                                    + decompress
                                    + "] is not expected ["
                                    + rawLen
                                    + "]");
                }
                parseRecord(bytes);
            } else {
                parseRecord(rawData);
            }
        } catch (InvalidProtocolBufferException e) {
            throw new LogProxyClientException(ErrorCode.E_PARSE, "Failed to read PB packet", e);
        }
    }

    /**
     * Do parse record data from an array of bytes to a {@link LogMessage} and add it into {@link
     * #recordQueue}.
     *
     * @param bytes An array of bytes of record data.
     * @throws LogProxyClientException If exception occurs.
     */
    private void parseRecord(byte[] bytes) throws LogProxyClientException {
        int offset = 0;
        while (offset < bytes.length) {
            int dataLength = Conversion.byteArrayToInt(bytes, offset + 4, 0, 0, 4);
            /*
             * We must copy a byte array and call parse after then,
             * or got a !!!RIDICULOUS EXCEPTION!!!,
             * if we wrap an unpooled buffer with offset and call setByteBuf just as same as `parse` function do.
             */
            LogMessage logMessage = new LogMessage(false);
            byte[] data = new byte[dataLength + 8];
            System.arraycopy(bytes, offset, data, 0, data.length);
            try {
                logMessage.parse(data);
            } catch (Exception e) {
                if (config.isIgnoreUnknownRecordType()) {
                    // unsupported type, ignore
                    logger.debug("Unsupported record type: {}", logMessage);
                    offset += (8 + dataLength);
                    continue;
                }
                throw new LogProxyClientException(ErrorCode.E_PARSE, e);
            }

            if (logger.isTraceEnabled()) {
                logger.trace("Log message: {}", logMessage);
            }

            while (true) {
                try {
                    recordQueue.put(new StreamContext.TransferPacket(logMessage));
                    break;
                } catch (InterruptedException e) {
                    // do nothing
                }
            }

            offset += (8 + dataLength);
        }
    }

    /** Discard the bytes in buffer. */
    protected final void discardSomeReadBytes() {
        if (buffer != null && !first && buffer.refCnt() == 1) {
            // discard some bytes if possible to make more room in the
            // buffer but only if the refCnt == 1  as otherwise the user may have
            // used slice().retain() or duplicate().retain().
            //
            // See:
            // - https://github.com/netty/netty/issues/2327
            // - https://github.com/netty/netty/issues/1764
            buffer.discardSomeReadBytes();
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        poolFlag = true;

        StreamContext context = ctx.channel().attr(ConnectionFactory.CONTEXT_KEY).get();
        stream = context.stream();
        config = context.config();
        params = context.params();
        recordQueue = context.recordQueue();
        clientHandlerV01 = new ClientHandlerV01(config, params, recordQueue, fastDecompressor);

        logger.info(
                "ClientId: {} connecting LogProxy: {}",
                params.info(),
                NetworkUtil.parseRemoteAddress(ctx.channel()));
        ctx.channel().writeAndFlush(generateConnectRequest(params.getProtocolVersion()));
    }

    /**
     * Generate the request body for protocol v2.
     *
     * @param version version of protocol
     * @return Request body.
     */
    public ByteBuf generateConnectRequest(ProtocolVersion version) {
        if (version.code() < ProtocolVersion.V2.code()) {
            return clientHandlerV01.generateConnectRequest();
        }

        LogProxyProto.ClientHandshakeRequest handShake =
                LogProxyProto.ClientHandshakeRequest.newBuilder()
                        .setLogType(params.getLogType().code())
                        .setIp(CLIENT_IP)
                        .setId(params.getClientId())
                        .setVersion(ClientUtil.getClientVersion())
                        .setEnableMonitor(params.isEnableMonitor())
                        .setConfiguration(params.getConfigurationString())
                        .build();

        byte[] packetBytes = handShake.toByteArray();
        ByteBuf byteBuf =
                ByteBufAllocator.DEFAULT.buffer(
                        MAGIC_STRING.length + 2 + 1 + 4 + packetBytes.length);
        byteBuf.writeBytes(MAGIC_STRING);
        byteBuf.writeShort(ProtocolVersion.V2.code());
        byteBuf.writeByte(HeaderType.HANDSHAKE_REQUEST_CLIENT.code());
        byteBuf.writeInt(packetBytes.length);
        byteBuf.writeBytes(packetBytes);
        return byteBuf;
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        poolFlag = false;

        logger.info(
                "Channel closed with ClientId: {}, LogProxy: {}",
                params.getClientId(),
                NetworkUtil.parseRemoteAddress(ctx.channel()));
        ctx.channel().disconnect();
        ctx.close();

        if (stream != null) {
            stream.triggerReconnect();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        poolFlag = false;
        resetState();

        logger.error(
                "Exception occurred ClientId: {}, with LogProxy: {}",
                params.info(),
                NetworkUtil.parseRemoteAddress(ctx.channel()),
                cause);
        ctx.channel().disconnect();
        ctx.close();

        if (stream != null) {
            if (cause instanceof LogProxyClientException) {
                stream.triggerException((LogProxyClientException) cause);
                if (((LogProxyClientException) cause).needStop()) {
                    stream.stop();
                }
            } else {
                stream.triggerReconnect();
            }
        }
    }
}
