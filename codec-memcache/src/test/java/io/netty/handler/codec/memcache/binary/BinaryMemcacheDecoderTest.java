/*
 * Copyright 2013 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.handler.codec.memcache.binary;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.memcache.LastMemcacheContent;
import io.netty.handler.codec.memcache.MemcacheContent;
import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.core.IsNull.notNullValue;
import static org.hamcrest.core.IsNull.nullValue;

/**
 * Verifies the correct functionality of the {@link BinaryMemcacheDecoder}.
 * <p/>
 * While technically there are both a {@link BinaryMemcacheRequestDecoder} and a {@link BinaryMemcacheResponseDecoder}
 * they implement the same basics and just differ in the type of headers returned.
 */
public class BinaryMemcacheDecoderTest {

    /**
     * Represents a GET request header with a key size of three.
     */
    private static final byte[] GET_REQUEST = {
        (byte) 0x80, 0x00, 0x00, 0x03,
        0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x03,
        0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00,
        0x66, 0x6f, 0x6f
    };

    private static final byte[] SET_REQUEST_WITH_CONTENT = {
        (byte) 0x80, 0x01, 0x00, 0x03,
        0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x0B,
        0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00,
        0x66, 0x6f, 0x6f,
        0x01, 0x02, 0x03, 0x04,
        0x05, 0x06, 0x07, 0x08
    };

    private EmbeddedChannel channel;

    @Before
    public void setup() throws Exception {
        channel = new EmbeddedChannel(new BinaryMemcacheRequestDecoder());
    }

    /**
     * This tests a simple GET request with a key as the value.
     */
    @Test
    public void shouldDecodeRequestWithSimpleValue() {
        ByteBuf incoming = Unpooled.buffer();
        incoming.writeBytes(GET_REQUEST);
        channel.writeInbound(incoming);

        BinaryMemcacheRequest request = (BinaryMemcacheRequest) channel.readInbound();

        assertThat(request, notNullValue());
        assertThat(request.getHeader(), notNullValue());
        assertThat(request.getKey(), notNullValue());
        assertThat(request.getExtras(), nullValue());

        BinaryMemcacheRequestHeader header = request.getHeader();
        assertThat(header.getKeyLength(), is((short) 3));
        assertThat(header.getExtrasLength(), is((byte) 0));
        assertThat(header.getTotalBodyLength(), is(3));

        request.release();
        assertThat(channel.readInbound(), instanceOf(LastMemcacheContent.class));
    }

    /**
     * This test makes sure that large content is emitted in chunks.
     */
    @Test
    public void shouldDecodeRequestWithChunkedContent() {
        int smallBatchSize = 2;
        channel = new EmbeddedChannel(new BinaryMemcacheRequestDecoder(smallBatchSize));

        ByteBuf incoming = Unpooled.buffer();
        incoming.writeBytes(SET_REQUEST_WITH_CONTENT);
        channel.writeInbound(incoming);

        BinaryMemcacheRequest request = (BinaryMemcacheRequest) channel.readInbound();

        assertThat(request, notNullValue());
        assertThat(request.getHeader(), notNullValue());
        assertThat(request.getKey(), notNullValue());
        assertThat(request.getExtras(), nullValue());

        BinaryMemcacheRequestHeader header = request.getHeader();
        assertThat(header.getKeyLength(), is((short) 3));
        assertThat(header.getExtrasLength(), is((byte) 0));
        assertThat(header.getTotalBodyLength(), is(11));

        int expectedContentChunks = 4;
        for (int i = 1; i <= expectedContentChunks; i++) {
            MemcacheContent content = (MemcacheContent) channel.readInbound();
            if (i < expectedContentChunks) {
                assertThat(content, instanceOf(MemcacheContent.class));
            } else {
                assertThat(content, instanceOf(LastMemcacheContent.class));
            }
            assertThat(content.content().readableBytes(), is(2));
        }
        assertThat(channel.readInbound(), nullValue());
    }

    /**
     * This test makes sure that even when the decoder is confronted with various chunk
     * sizes in the middle of decoding, it can recover and decode all the time eventually.
     */
    @Test
    public void shouldHandleNonUniformNetworkBatches() {
        ByteBuf incoming = Unpooled.copiedBuffer(SET_REQUEST_WITH_CONTENT);
        while (incoming.isReadable()) {
            channel.writeInbound(incoming.readBytes(5));
        }

        BinaryMemcacheRequest request = (BinaryMemcacheRequest) channel.readInbound();

        assertThat(request, notNullValue());
        assertThat(request.getHeader(), notNullValue());
        assertThat(request.getKey(), notNullValue());
        assertThat(request.getExtras(), nullValue());

        MemcacheContent content1 = (MemcacheContent) channel.readInbound();
        MemcacheContent content2 = (MemcacheContent) channel.readInbound();

        assertThat(content1, instanceOf(MemcacheContent.class));
        assertThat(content2, instanceOf(LastMemcacheContent.class));

        assertThat(content1.content().readableBytes(), is(3));
        assertThat(content2.content().readableBytes(), is(5));
    }

    /**
     * This test makes sure that even when more requests arrive in the same batch, they
     * get emitted as separate messages.
     */
    @Test
    public void shouldHandleTwoMessagesInOneBatch() {
        channel.writeInbound(Unpooled.buffer().writeBytes(GET_REQUEST).writeBytes(GET_REQUEST));

        BinaryMemcacheRequest request = (BinaryMemcacheRequest) channel.readInbound();
        assertThat(request, instanceOf(BinaryMemcacheRequest.class));
        assertThat(request, notNullValue());
        assertThat(channel.readInbound(), instanceOf(LastMemcacheContent.class));

        request = (BinaryMemcacheRequest) channel.readInbound();
        assertThat(request, instanceOf(BinaryMemcacheRequest.class));
        assertThat(request, notNullValue());
        assertThat(channel.readInbound(), instanceOf(LastMemcacheContent.class));
    }

}
