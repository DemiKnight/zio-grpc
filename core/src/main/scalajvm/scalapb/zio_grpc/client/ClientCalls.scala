package scalapb.zio_grpc.client

import io.grpc.{CallOptions, MethodDescriptor, StatusException}
import scalapb.zio_grpc.{ResponseContext, ResponseFrame, SafeMetadata, ZChannel}
import zio.stream.ZStream
import zio.{Exit, IO, ZIO}

object ClientCalls {
  object withMetadata {

    private def unaryCall[Req, Res](
        call: ZClientCall[Req, Res],
        headers: SafeMetadata,
        req: Req
    ): IO[StatusException, ResponseContext[Res]] =
      ZIO.acquireReleaseExitWith(UnaryClientCallListener.make[Res])(exitHandler(call)) { listener =>
        call.start(listener, headers) *>
          call.request(1) *>
          call.sendMessage(req) *>
          call.halfClose() *>
          listener.getValue
      }

    def unaryCall[Req, Res](
        channel: ZChannel,
        method: MethodDescriptor[Req, Res],
        options: CallOptions,
        headers: SafeMetadata,
        req: Req
    ): IO[StatusException, ResponseContext[Res]] =
      channel
        .newCall(method, options)
        .flatMap(unaryCall(_, headers, req))

    private def serverStreamingCall[Req, Res](
        call: ZClientCall[Req, Res],
        headers: SafeMetadata,
        req: Req
    ): ZStream[Any, StatusException, ResponseFrame[Res]] =
      ZStream
        .acquireReleaseExitWith(
          StreamingClientCallListener.make[Res](call)
        )(anyExitHandler[Req, Res](call))
        .flatMap { (listener: StreamingClientCallListener[Res]) =>
          ZStream
            .fromZIO(
              call.start(listener, headers) *>
                call.request(1) *>
                call.sendMessage(req) *>
                call.halfClose()
            )
            .drain ++ listener.stream
        }

    def serverStreamingCall[Req, Res](
        channel: ZChannel,
        method: MethodDescriptor[Req, Res],
        options: CallOptions,
        headers: SafeMetadata,
        req: Req
    ): ZStream[Any, StatusException, ResponseFrame[Res]] =
      ZStream
        .fromZIO(channel.newCall(method, options))
        .flatMap(serverStreamingCall(_, headers, req))

    private def clientStreamingCall[Req, Res](
        call: ZClientCall[Req, Res],
        headers: SafeMetadata,
        req: ZStream[Any, StatusException, Req]
    ): IO[StatusException, ResponseContext[Res]] =
      ZIO.acquireReleaseExitWith(UnaryClientCallListener.make[Res])(exitHandler(call)) { listener =>
        val callStream   = req.tap(call.sendMessage).drain ++ ZStream.fromZIO(call.halfClose()).drain
        val resultStream = ZStream.fromZIO(listener.getValue)

        call.start(listener, headers) *>
          call.request(1) *>
          callStream
            .merge(resultStream)
            .runCollect
            .map(res => res.last)
      }

    def clientStreamingCall[Req, Res](
        channel: ZChannel,
        method: MethodDescriptor[Req, Res],
        options: CallOptions,
        headers: SafeMetadata,
        req: ZStream[Any, StatusException, Req]
    ): IO[StatusException, ResponseContext[Res]] =
      channel
        .newCall(method, options)
        .flatMap(
          clientStreamingCall(
            _,
            headers,
            req
          )
        )

    private def bidiCall[Req, Res](
        call: ZClientCall[Req, Res],
        headers: SafeMetadata,
        req: ZStream[Any, StatusException, Req]
    ): ZStream[Any, StatusException, ResponseFrame[Res]] =
      ZStream
        .acquireReleaseExitWith(
          StreamingClientCallListener.make[Res](call)
        )(anyExitHandler(call))
        .flatMap { (listener: StreamingClientCallListener[Res]) =>
          val init              =
            ZStream
              .fromZIO(
                call.start(listener, headers) *>
                  call.request(1)
              )
          val finish            = ZStream.fromZIO(call.halfClose())
          val sendRequestStream = (init ++ req.tap(call.sendMessage) ++ finish).drain
          sendRequestStream.merge(listener.stream, ZStream.HaltStrategy.Right)
        }

    def bidiCall[Req, Res](
        channel: ZChannel,
        method: MethodDescriptor[Req, Res],
        options: CallOptions,
        headers: SafeMetadata,
        req: ZStream[Any, StatusException, Req]
    ): ZStream[Any, StatusException, ResponseFrame[Res]] =
      ZStream
        .fromZIO(
          channel.newCall(method, options)
        )
        .flatMap(bidiCall(_, headers, req))
  }

  def exitHandler[Req, Res](
      call: ZClientCall[Req, Res]
  )(l: Any, ex: Exit[StatusException, Any]) = anyExitHandler(call)(l, ex)

  // less type safe
  def anyExitHandler[Req, Res](
      call: ZClientCall[Req, Res]
  ) =
    (_: Any, ex: Exit[Any, Any]) =>
      ZIO.when(!ex.isSuccess) {
        call.cancel("Interrupted").ignore
      }

  def unaryCall[Req, Res](
      channel: ZChannel,
      method: MethodDescriptor[Req, Res],
      options: CallOptions,
      headers: SafeMetadata,
      req: Req
  ): IO[StatusException, Res] =
    withMetadata.unaryCall(channel, method, options, headers, req).map(_.response)

  def serverStreamingCall[Req, Res](
      channel: ZChannel,
      method: MethodDescriptor[Req, Res],
      options: CallOptions,
      headers: SafeMetadata,
      req: Req
  ): ZStream[Any, StatusException, Res] =
    withMetadata
      .serverStreamingCall(channel, method, options, headers, req)
      .collect { case ResponseFrame.Message(x) => x }

  def clientStreamingCall[Req, Res](
      channel: ZChannel,
      method: MethodDescriptor[Req, Res],
      options: CallOptions,
      headers: SafeMetadata,
      req: ZStream[Any, StatusException, Req]
  ): IO[StatusException, Res] =
    withMetadata.clientStreamingCall(channel, method, options, headers, req).map(_.response)

  def bidiCall[Req, Res](
      channel: ZChannel,
      method: MethodDescriptor[Req, Res],
      options: CallOptions,
      headers: SafeMetadata,
      req: ZStream[Any, StatusException, Req]
  ): ZStream[Any, StatusException, Res] =
    withMetadata
      .bidiCall(channel, method, options, headers, req)
      .collect { case ResponseFrame.Message(x) => x }
}
