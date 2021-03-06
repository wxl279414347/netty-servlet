package com.github.netty.protocol.nrpc;

import com.github.netty.annotation.Protocol;
import com.github.netty.core.AbstractChannelHandler;
import com.github.netty.core.util.ClassFileMethodToParameterNamesFunction;
import com.github.netty.core.util.RecyclableUtil;
import com.github.netty.core.util.ReflectUtil;
import com.github.netty.core.util.StringUtil;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static com.github.netty.protocol.nrpc.DataCodec.Encode.BINARY;
import static com.github.netty.protocol.nrpc.RpcPacket.*;
import static com.github.netty.protocol.nrpc.RpcServerAop.CONTEXT_LOCAL;

/**
 * Server side processor
 * @author wangzihao
 *  2018/9/16/016
 */
public class RpcServerChannelHandler extends AbstractChannelHandler<RpcPacket,Object> {
    /**
     * Data encoder decoder. (Serialization or Deserialization)
     */
    private DataCodec dataCodec;
    private final Map<String, RpcServerInstance> serviceInstanceMap = new HashMap<>();
    private final List<RpcServerAop> nettyRpcServerAopList = new CopyOnWriteArrayList<>();
    private ChannelHandlerContext context;

    public RpcServerChannelHandler() {
        this(new JsonDataCodec());
    }

    public RpcServerChannelHandler(DataCodec dataCodec) {
        super(true);
        this.dataCodec = dataCodec;
        dataCodec.getEncodeRequestConsumerList().add(params -> {
            RpcContext<RpcServerInstance> rpcContext = CONTEXT_LOCAL.get();
            for (RpcServerAop aop : nettyRpcServerAopList) {
                aop.onDecodeRequestBefore(rpcContext,params);
            }
        });
    }

    public List<RpcServerAop> getAopList() {
        return nettyRpcServerAopList;
    }

    public DataCodec getDataCodec() {
        return dataCodec;
    }

    public ChannelHandlerContext getContext() {
        return context;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        this.context = ctx;
        for (RpcServerAop aop : nettyRpcServerAopList) {
            aop.onConnectAfter(this);
        }
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        for (RpcServerAop aop : nettyRpcServerAopList) {
            aop.onDisconnectAfter(this);
        }
        super.channelInactive(ctx);
    }

    @Override
    protected void onMessageReceived(ChannelHandlerContext ctx, RpcPacket packet) throws Exception {
        try {
            if (packet instanceof RequestPacket) {
                RpcContext<RpcServerInstance> rpcContext = CONTEXT_LOCAL.get();
                if(rpcContext == null){
                    rpcContext = new RpcContext<>();
                    CONTEXT_LOCAL.set(rpcContext);
                }else {
                    rpcContext.recycle();
                }
                try {
                    onRequestReceived(ctx, (RequestPacket) packet,rpcContext);
                }finally {
                    try {
                        for (RpcServerAop aop : nettyRpcServerAopList) {
                            aop.onResponseAfter(rpcContext);
                        }
                    }finally {
                        rpcContext.recycle();
                    }
                }
            } else {
                if (packet.getAck() == ACK_YES) {
                    RpcPacket responsePacket = new RpcPacket(RpcPacket.TYPE_PONG);
                    responsePacket.setAck(ACK_NO);
                    ctx.writeAndFlush(responsePacket).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
                }
            }
        }finally {
            packet.recycle();
        }
    }

    protected void onRequestReceived(ChannelHandlerContext ctx, RequestPacket request, RpcContext<RpcServerInstance> rpcContext){
        rpcContext.setRemoteAddress((InetSocketAddress) ctx.channel().remoteAddress());
        rpcContext.setLocalAddress((InetSocketAddress) ctx.channel().localAddress());
        rpcContext.setRequest(request);
        RpcServerInstance rpcInstance = serviceInstanceMap.get(request.getRequestMappingName());
        if(rpcInstance == null) {
            if(request.getAck() == ACK_YES) {
                ResponsePacket response = ResponsePacket.newInstance();
                rpcContext.setResponse(response);
                boolean release = true;
                try {
                    response.setRequestId(request.getRequestId());
                    response.setEncode(BINARY);
                    response.setStatus(ResponsePacket.NO_SUCH_SERVICE);
                    response.setMessage("not found service " + request.getRequestMappingName());

                    ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
                    release = false;
                }finally {
                    if(release) {
                        RecyclableUtil.release(response);
                    }
                }
            }
        }else {
            ResponsePacket response = rpcInstance.invoke(request,rpcContext);
            if (request.getAck() == ACK_YES) {
                ctx.writeAndFlush(response)
                        .addListener(ChannelFutureListener.CLOSE_ON_FAILURE);
            } else {
                RecyclableUtil.release(response);
            }
        }
    }

    /**
     * Increase the instance
     * @param instance The implementation class
     */
    public void addInstance(Object instance){
        addInstance(instance,getRequestMappingName(instance.getClass()));
    }

    /**
     * Increase the instance
     * @param instance The implementation class
     * @param requestMappingName requestMappingName
     */
    public void addInstance(Object instance,String requestMappingName){
        addInstance(instance,requestMappingName,new ClassFileMethodToParameterNamesFunction());
    }

    /**
     * Increase the instance
     * @param instance The implementation class
     * @param requestMappingName requestMappingName
     * @param methodToParameterNamesFunction Method to a function with a parameter name
     */
    public void addInstance(Object instance,String requestMappingName,Function<Method,String[]> methodToParameterNamesFunction){
        if(requestMappingName == null || requestMappingName.isEmpty()){
            requestMappingName = generateRequestMappingName(instance.getClass());
        }
        synchronized (serviceInstanceMap) {
            RpcServerInstance rpcServerInstance = new RpcServerInstance(instance,dataCodec,methodToParameterNamesFunction);
            RpcServerInstance oldServerInstance = serviceInstanceMap.put(requestMappingName,rpcServerInstance);

            if (oldServerInstance != null) {
                Object oldInstance = oldServerInstance.getInstance();
                logger.warn("override instance old={}, new={}",
                        oldInstance.getClass().getSimpleName() +"@"+ Integer.toHexString(oldInstance.hashCode()),
                        instance.getClass().getSimpleName() +"@"+  Integer.toHexString(instance.hashCode()));
            }
        }

        logger.trace("addInstance({}, {}, {})",
                requestMappingName,
                instance.getClass().getSimpleName(),
                methodToParameterNamesFunction.getClass().getSimpleName());
    }

    /**
     * Is there an instance
     * @param instance instance
     * @return boolean existInstance
     */
    public boolean existInstance(Object instance){
        if(serviceInstanceMap.isEmpty()){
            return false;
        }
        Collection<RpcServerInstance> values = serviceInstanceMap.values();
        for(RpcServerInstance rpcServerInstance : values){
            if(rpcServerInstance.getInstance() == instance){
                return true;
            }
        }
        return false;
    }

    public Map<String, RpcServerInstance> getServiceInstanceMap() {
        return Collections.unmodifiableMap(serviceInstanceMap);
    }

    /**
     * Get the service name
     * @param instanceClass instanceClass
     * @return requestMappingName
     */
    public static String getRequestMappingName(Class instanceClass){
        String requestMappingName = null;
        Protocol.RpcService rpcInterfaceAnn = ReflectUtil.findAnnotation(instanceClass, Protocol.RpcService.class);
        if (rpcInterfaceAnn != null) {
            requestMappingName = rpcInterfaceAnn.value();
        }
        return requestMappingName;
    }

    /**
     * Generate a service name
     * @param instanceClass instanceClass
     * @return requestMappingName
     */
    public static String generateRequestMappingName(Class instanceClass){
        String requestMappingName;
        Class[] classes = ReflectUtil.getInterfaces(instanceClass);
        if(classes.length > 0){
            requestMappingName = '/'+ StringUtil.firstLowerCase(classes[0].getSimpleName());
        }else {
            requestMappingName =  '/'+ StringUtil.firstLowerCase(instanceClass.getSimpleName());
        }
        return requestMappingName;
    }
}
