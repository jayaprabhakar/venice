package com.linkedin.venice.router;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkedin.venice.acl.AccessController;
import com.linkedin.venice.acl.AclException;
import com.linkedin.venice.router.api.VenicePathParserHelper;
import com.linkedin.venice.router.stats.AdminOperationsStats;
import com.linkedin.venice.utils.NettyUtils;
import com.linkedin.venice.utils.RedundantExceptionFilter;
import com.linkedin.venice.utils.Utils;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.ReferenceCountUtil;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.apache.log4j.Logger;

import static com.linkedin.venice.router.api.VenicePathParser.*;
import static com.linkedin.venice.utils.NettyUtils.*;
import static io.netty.handler.codec.http.HttpResponseStatus.*;


@ChannelHandler.Sharable
public class AdminOperationsHandler extends SimpleChannelInboundHandler<HttpRequest> {
  private static final Logger logger = Logger.getLogger(AdminOperationsHandler.class);
  private static final byte[] EMPTY_BYTES = new byte[0];
  private static final RedundantExceptionFilter filter = RedundantExceptionFilter.getRedundantExceptionFilter();
  private static final ObjectMapper mapper = new ObjectMapper();

  public static final String READ_THROTTLING_ENABLED = "readThrottlingEnabled";
  public static final String EARLY_THROTTLE_ENABLED = "earlyThrottleEnabled";

  private final AccessController accessController;
  private final AdminOperationsStats adminOperationsStats;
  private final RouterServer routerServer;
  private final VeniceRouterConfig routerConfig;
  private final ScheduledExecutorService executor;
  private ScheduledFuture routerReadQuotaThrottlingLeaseFuture;

  private final boolean initialReadThrottlingEnabled;
  private final boolean initialEarlyThrottleEnabled;

  public AdminOperationsHandler(AccessController accessController, RouterServer server, AdminOperationsStats adminOperationsStats) {
    this.accessController = accessController;
    this.adminOperationsStats = adminOperationsStats;
    routerServer = server;
    routerConfig = server.getConfig();
    routerReadQuotaThrottlingLeaseFuture = null;

    initialReadThrottlingEnabled = routerConfig.isReadThrottlingEnabled();
    initialEarlyThrottleEnabled = routerConfig.isEarlyThrottleEnabled();

    if (initialReadThrottlingEnabled || initialEarlyThrottleEnabled) {
      executor = Executors.newSingleThreadScheduledExecutor();
    } else {
      executor = null;
    }
  }

  @Override
  public void channelRead0(ChannelHandlerContext ctx, HttpRequest req) throws IOException {
    HttpMethod method = req.method();

    VenicePathParserHelper pathHelper = new VenicePathParserHelper(req.uri());
    final String resourceType = pathHelper.getResourceType();
    final String adminTask = pathHelper.getResourceName();

    if (!resourceType.equals(TYPE_ADMIN)) {
      // Pass request to the next channel if it's not an admin operation
      ReferenceCountUtil.retain(req);
      ctx.fireChannelRead(req);
      return;
    }

    adminOperationsStats.recordAdminRequest();

    // Since AdminOperationsHandler comes after health check, it should not receive requests without a task
    if(Utils.isNullOrEmpty(adminTask)) {
      adminOperationsStats.recordErrorAdminRequest();
      sendUserErrorResponse("Admin operations must specify a task", ctx);
      return;
    }

    if (accessController != null) {
      try {
        SslHandler sslHandler = ctx.pipeline().get(SslHandler.class);
        if (sslHandler == null) {
          throw new AclException("Non SSL Admin request received");
        }
        X509Certificate clientCert = (X509Certificate) sslHandler.engine().getSession().getPeerCertificates()[0];
        accessController.hasAccessToAdminOperation(clientCert, adminTask);
      } catch (AclException e) {
        String client = ctx.channel().remoteAddress().toString(); //ip and port
        String errLine = String.format("%s requested %s %s", client, method, req.uri());

        logger.warn("Exception occurred! Access rejected: " + errLine + "\n" + e);
        sendErrorResponse(HttpResponseStatus.FORBIDDEN, "Access Rejected", ctx);
        return;
      }
    }

    logger.info("Received admin operation request from " + ctx.channel().remoteAddress() + ". Method: " + method + " Task: " + adminTask + " Action: " + pathHelper.getKey());

    if (HttpMethod.GET.equals(method)) {
      handleGet(pathHelper, ctx);
    } else if (HttpMethod.POST.equals(method)) {
      handlePost(pathHelper, ctx);
    } else {
      sendUserErrorResponse("Unsupported request method " + method, ctx);
    }
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable e) {
    adminOperationsStats.recordErrorAdminRequest();
    InetSocketAddress sockAddr = (InetSocketAddress)(ctx.channel().remoteAddress());
    String remoteAddr = sockAddr.getHostName() + ":" + sockAddr.getPort();
    if (!filter.isRedundantException(sockAddr.getHostName(), e)) {
      logger.error("Got exception while handling admin operation request from " + remoteAddr + ", and error: " + e.getMessage());
    }
    setupResponseAndFlush(INTERNAL_SERVER_ERROR, EMPTY_BYTES, false, ctx);
    ctx.close();
  }

  private void handleGet(VenicePathParserHelper pathHelper, ChannelHandlerContext ctx) throws IOException {
    final String task = pathHelper.getResourceName();
    final String action = pathHelper.getKey();

    if (TASK_READ_QUOTA_THROTTLE.equals(task)) {
      if (Utils.isNullOrEmpty(action)) {
        sendReadQuotaThrottleStatus(ctx);
      } else {
        sendUserErrorResponse("GET admin task " + TASK_READ_QUOTA_THROTTLE + " can not specify an action", ctx);
      }
    } else {
      sendUnimplementedErrorResponse(task, ctx);
    }
  }

  private void handlePost(VenicePathParserHelper pathHelper, ChannelHandlerContext ctx) throws IOException {
    final String task = pathHelper.getResourceName();
    final String action = pathHelper.getKey();

    if (Utils.isNullOrEmpty(action)) {
      sendUserErrorResponse("Admin operations must have an action", ctx);
      return;
    }

    if (TASK_READ_QUOTA_THROTTLE.equals(task)) {
      if (ACTION_ENABLE.equals(action)) {
        // A REST call to enable quota will only enable it if the router was initially configured to enable quota
        resetReadQuotaThrottling();
        sendReadQuotaThrottleStatus(ctx);
      } else if (ACTION_DISABLE.equals(action)) {
        disableReadQuotaThrottling();
        sendReadQuotaThrottleStatus(ctx);
      } else {
        sendUserErrorResponse("Unsupported action " + action + " for task " + task, ctx);
      }
    } else {
      sendUnimplementedErrorResponse(task, ctx);
    }
  }

  private void resetReadQuotaThrottling() {
    routerConfig.setReadThrottlingEnabled(initialReadThrottlingEnabled);
    routerConfig.setEarlyThrottleEnabled(initialEarlyThrottleEnabled);
    routerServer.setReadRequestThrottling(initialReadThrottlingEnabled);
  }

  /**
   * This method temporarily disables read quota throttling. When disabling the read quota throttling, a lease is set.
   * When the lease expires, read quota throttling is reset to its initial state.
   */
  private void disableReadQuotaThrottling() {
    if (routerReadQuotaThrottlingLeaseFuture != null && !routerReadQuotaThrottlingLeaseFuture.isDone()) {
      logger.info("Cancelling existing read quota timer.");
      routerReadQuotaThrottlingLeaseFuture.cancel(true);
    }

    routerConfig.setReadThrottlingEnabled(false);
    routerServer.setReadRequestThrottling(false);
    routerConfig.setEarlyThrottleEnabled(false);

    if (initialReadThrottlingEnabled || initialEarlyThrottleEnabled) {
      routerReadQuotaThrottlingLeaseFuture =
          executor.schedule(this::resetReadQuotaThrottling, routerConfig.getReadQuotaThrottlingLeaseTimeoutMs(), TimeUnit.MILLISECONDS);
    }
  }

  private void sendReadQuotaThrottleStatus(ChannelHandlerContext ctx) throws IOException {
    HashMap<String, String> payload = new HashMap<>();
    payload.put(READ_THROTTLING_ENABLED, String.valueOf(routerConfig.isReadThrottlingEnabled()));
    payload.put(EARLY_THROTTLE_ENABLED, String.valueOf(routerConfig.isEarlyThrottleEnabled()));

    sendSuccessResponse(payload, ctx);
  }

  private void sendUserErrorResponse(String message, ChannelHandlerContext ctx) throws IOException {
    adminOperationsStats.recordErrorAdminRequest();
    HttpResponseStatus status = BAD_REQUEST;
    String errorPrefix = "Error " + status.code() + ": ";
    String errorDesc = message + ". Bad request (not conforming to supported command patterns)!";
    sendErrorResponse(status, errorPrefix + errorDesc, ctx);
  }

  private void sendSuccessResponse(Map<String, String> payload, ChannelHandlerContext ctx) throws IOException {
    sendResponse(OK, payload, ctx);
  }

  private void sendUnimplementedErrorResponse(String task, ChannelHandlerContext ctx) throws IOException {
    adminOperationsStats.recordErrorAdminRequest();
    HttpResponseStatus status = NOT_IMPLEMENTED;
    String errorPrefix = "Error " + status.code() + ": ";
    String errorDesc = "Request " + task + " unimplemented !";
    sendErrorResponse(status, errorPrefix + errorDesc, ctx);
  }

  private void sendErrorResponse(HttpResponseStatus status, String errorMsg, ChannelHandlerContext ctx) throws IOException {
    adminOperationsStats.recordErrorAdminRequest();
    Map<String, String> payload = new HashMap<>();
    payload.put("error", errorMsg);

    sendResponse(status, payload, ctx);
  }

  private void sendResponse(HttpResponseStatus status, Map<String, String> payload, ChannelHandlerContext ctx)
      throws JsonProcessingException {
    if (payload == null) {
      setupResponseAndFlush(status, EMPTY_BYTES, true, ctx);
    } else {
      setupResponseAndFlush(status, mapper.writeValueAsString(payload).getBytes(), true, ctx);
    }
  }
}