package com.github.netty.protocol.servlet;

import com.github.netty.core.util.CompositeByteBufX;
import com.github.netty.core.util.HttpHeaderUtil;
import com.github.netty.core.util.IOUtil;
import com.github.netty.core.util.Recyclable;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.*;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.stream.ChunkedInput;
import io.netty.handler.stream.ChunkedWriteHandler;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Servlet output stream (segmented)
 * @author wangzihao
 */
public class ServletOutputChunkedStream extends ServletOutputStream {
    private final ByteChunkedInput chunkedInput = new ByteChunkedInput();
    private final AtomicBoolean flushIngFlag = new AtomicBoolean(false);


    protected ServletOutputChunkedStream() {}

    @Override
    public void flush() throws IOException {
        checkClosed();

        if(getBuffer() == null) {
            return;
        }
        asyncFlush(null);
    }

    @Override
    public void close() throws IOException {
        if(super.isClosed.compareAndSet(false,true)){
            chunkedInput.setCloseInputFlag(true);
            CloseListener closeListener = super.getCloseListener();
            closeListener.addRecycleConsumer(o -> chunkedInput.recycle());
            asyncFlush(closeListener);
        }
    }

    /**
     * Async refresh buffer (listen for completion events)
     * @param listener Called after refreshing the buffer
     */
    private void asyncFlush(ChannelFutureListener listener) {
        if(super.isSendResponseIng){
            return;
        }

        if (super.isSendResponseHeader.compareAndSet(false,true)) {
            sendChunkedResponse(listener);
            return;
        }

        if(flushIngFlag.compareAndSet(false,true)){
            try {
                bufferTransformToChunk();
                if (chunkedInput.hasChunk()) {
                    ChannelHandlerContext channelHandlerContext = getServletHttpExchange().getChannelHandlerContext();
                    if (channelHandlerContext.channel().isActive()) {
                        channelHandlerContext.flush();
                    }
                }
            }finally {
                flushIngFlag.set(false);
            }
        }

        if (listener != null) {
            try {
                listener.operationComplete(null);
            } catch (Exception e) {
                //inner error
                e.printStackTrace();
            }
        }
    }

    private void bufferTransformToChunk(){
        try {
            lock();
            ByteBuf content = getBuffer();
            if (content != null) {
                this.chunkedInput.setChunkByteBuf(content);
                setBuffer(null);
            }
        }finally {
            unlock();
        }
    }

    private void sendChunkedResponse(ChannelFutureListener listener){
        NettyHttpResponse nettyResponse = getServletHttpExchange().getResponse().getNettyResponse();
        LastHttpContent lastHttpContent = nettyResponse.enableTransferEncodingChunked();
        chunkedInput.setLastHttpContent(lastHttpContent);

        ChannelPipeline pipeline = getServletHttpExchange().getChannelHandlerContext().channel().pipeline();
        if(pipeline.context(ChunkedWriteHandler.class) == null) {
            ChannelHandlerContext httpContext = pipeline.context(HttpServerCodec.class);
            if(httpContext == null){
                httpContext = pipeline.context(HttpRequestDecoder.class);
            }
            if(httpContext != null) {
                ChunkedWriteHandler chunkedWriteHandler = new ChunkedWriteHandler();
                try {
                    chunkedWriteHandler.handlerAdded(httpContext);
                } catch (Exception e) {
                    //skip
                }
                pipeline.addAfter(
                        httpContext.name(), "ChunkedWrite",chunkedWriteHandler);
            }
        }

        //see Http11Processor#prepareResponse
        super.sendResponse().addListener((ChannelFutureListener) future -> {
            if(!future.isSuccess()){
                future.channel().close().addListener(listener);
                return;
            }

            if(!flushIngFlag.compareAndSet(false,true)){
                return;
            }
            try {
                Channel channel = future.channel();
                ServletHttpServletResponse response = getServletHttpExchange().getResponse();
                long contentLength = response.getContentLength();
                if(contentLength >= 0){
                    CompositeByteBufX content = getBuffer();
                    if(content != null){
                        IOUtil.writerModeToReadMode(content);
                        if(listener == null){
                            channel.writeAndFlush(new DefaultLastHttpContent(content), channel.voidPromise());
                        }else {
                            channel.writeAndFlush(new DefaultLastHttpContent(content)).addListener(listener);
                        }
                    }else {
                        channel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT).addListener(listener);
                    }
                }else {
                    bufferTransformToChunk();
                    if (chunkedInput.hasChunk()) {
                        if (!channel.isActive()) {
                            return;
                        }
                        ChannelPromise promise;
                        if (listener == null) {
                            promise = channel.voidPromise();
                        } else {
                            promise = channel.newProgressivePromise();
                            promise.addListener(listener);
                        }
                        channel.writeAndFlush(chunkedInput, promise);

                    } else if (listener != null) {
                        listener.operationComplete(future);
                    }
                }
            } finally {
                flushIngFlag.set(false);
            }
        });
    }

    static class ByteChunkedInput implements ChunkedInput<Object>, Recyclable {
        private boolean closeInputFlag = false;
        private boolean sendLastChunkFlag = false;
        private AtomicInteger readLength = new AtomicInteger();
        private ByteBuf chunkByteBuf;
        private LastHttpContent lastHttpContent;

        @Override
        public long length() {
            if(closeInputFlag){
                return readLength.get();
            }
            return -1;
        }

        @Override
        public long progress() {
            if(closeInputFlag){
                return readLength.get();
            }
            return -1;
        }

        @Override
        public boolean isEndOfInput() throws Exception {
            if (closeInputFlag) {
                // Only end of input after last HTTP chunk has been sent
                return sendLastChunkFlag;
            } else {
                return false;
            }
        }

        @Override
        public void close() throws Exception {
            chunkByteBuf = null;
        }

        @Deprecated
        @Override
        public Object readChunk(ChannelHandlerContext ctx) throws Exception {
            return readChunk(ctx.alloc());
        }

        @Override
        public Object readChunk(ByteBufAllocator allocator) throws Exception {
            if (sendLastChunkFlag) {
                return null;
            }

            ByteBuf byteBuf = chunkByteBuf;
            IOUtil.writerModeToReadMode(byteBuf);
            Object result;
            if (closeInputFlag) {
                // Send last chunk for this input
                sendLastChunkFlag = true;
                if(lastHttpContent != null){
                    //Trailer does not support the removal of the head of field
                    HttpHeaderUtil.removeHeaderUnSupportTrailer(lastHttpContent);
                    if(byteBuf != null){
                        DefaultLastHttpContent lastHttpContentCopy = new DefaultLastHttpContent(byteBuf, false);
                        lastHttpContentCopy.trailingHeaders().set(lastHttpContent.trailingHeaders());
                        result = lastHttpContentCopy;
                    }else {
                        result = lastHttpContent;
                    }
                }else if(byteBuf != null){
                    result = new DefaultLastHttpContent(byteBuf);
                }else {
                    result = LastHttpContent.EMPTY_LAST_CONTENT;
                }
            } else {
                result = byteBuf;
            }
            if(byteBuf != null) {
                readLength.addAndGet(byteBuf.capacity());
            }
            chunkByteBuf = null;
            return result;
        }

        public void setLastHttpContent(LastHttpContent lastHttpContent) {
            this.lastHttpContent = lastHttpContent;
        }

        public void setCloseInputFlag(boolean isCloseInput) {
            this.closeInputFlag = isCloseInput;
        }

        public void setChunkByteBuf(ByteBuf chunkByteBuf) {
            //Switching the read mode
            IOUtil.writerModeToReadMode(chunkByteBuf);
            this.chunkByteBuf = chunkByteBuf;
        }

        public boolean hasChunk() {
            if(sendLastChunkFlag){
                return false;
            }
            if(closeInputFlag){
                return true;
            }
            return chunkByteBuf != null;
        }

        @Override
        public void recycle() {
            this.closeInputFlag = false;
            this.sendLastChunkFlag = false;
            this.readLength.set(0);
            this.chunkByteBuf = null;
            this.lastHttpContent = null;
        }
    }
}
