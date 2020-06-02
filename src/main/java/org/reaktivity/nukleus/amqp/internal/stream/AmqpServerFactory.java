/**
 * Copyright 2016-2020 The Reaktivity Project
 *
 * The Reaktivity Project licenses this file to you under the Apache License,
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
package org.reaktivity.nukleus.amqp.internal.stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static org.reaktivity.nukleus.amqp.internal.types.AmqpCapabilities.RECEIVE_ONLY;
import static org.reaktivity.nukleus.amqp.internal.types.AmqpCapabilities.SEND_ONLY;
import static org.reaktivity.nukleus.amqp.internal.types.codec.AmqpDescribedType.APPLICATION_PROPERTIES;
import static org.reaktivity.nukleus.amqp.internal.types.codec.AmqpDescribedType.MESSAGE_ANNOTATIONS;
import static org.reaktivity.nukleus.amqp.internal.types.codec.AmqpDescribedType.PROPERTIES;
import static org.reaktivity.nukleus.amqp.internal.types.codec.AmqpErrorType.DECODE_ERROR;
import static org.reaktivity.nukleus.amqp.internal.types.codec.AmqpErrorType.NOT_ALLOWED;
import static org.reaktivity.nukleus.amqp.internal.util.AmqpTypeUtil.amqpCapabilities;
import static org.reaktivity.nukleus.amqp.internal.util.AmqpTypeUtil.amqpReceiverSettleMode;
import static org.reaktivity.nukleus.amqp.internal.util.AmqpTypeUtil.amqpRole;
import static org.reaktivity.nukleus.amqp.internal.util.AmqpTypeUtil.amqpSenderSettleMode;
import static org.reaktivity.nukleus.budget.BudgetCreditor.NO_CREDITOR_INDEX;
import static org.reaktivity.nukleus.buffer.BufferPool.NO_SLOT;

import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.LongUnaryOperator;
import java.util.function.ToIntFunction;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.collections.Int2ObjectHashMap;
import org.agrona.collections.Long2ObjectHashMap;
import org.agrona.collections.MutableInteger;
import org.agrona.concurrent.UnsafeBuffer;
import org.reaktivity.nukleus.amqp.internal.AmqpConfiguration;
import org.reaktivity.nukleus.amqp.internal.AmqpNukleus;
import org.reaktivity.nukleus.amqp.internal.types.AmqpAnnotationFW;
import org.reaktivity.nukleus.amqp.internal.types.AmqpApplicationPropertyFW;
import org.reaktivity.nukleus.amqp.internal.types.AmqpCapabilities;
import org.reaktivity.nukleus.amqp.internal.types.AmqpPropertiesFW;
import org.reaktivity.nukleus.amqp.internal.types.ArrayFW;
import org.reaktivity.nukleus.amqp.internal.types.BoundedOctetsFW;
import org.reaktivity.nukleus.amqp.internal.types.Flyweight;
import org.reaktivity.nukleus.amqp.internal.types.ListFW;
import org.reaktivity.nukleus.amqp.internal.types.OctetsFW;
import org.reaktivity.nukleus.amqp.internal.types.StringFW;
import org.reaktivity.nukleus.amqp.internal.types.codec.AmqpAttachFW;
import org.reaktivity.nukleus.amqp.internal.types.codec.AmqpBeginFW;
import org.reaktivity.nukleus.amqp.internal.types.codec.AmqpBinaryFW;
import org.reaktivity.nukleus.amqp.internal.types.codec.AmqpByteFW;
import org.reaktivity.nukleus.amqp.internal.types.codec.AmqpCloseFW;
import org.reaktivity.nukleus.amqp.internal.types.codec.AmqpDescribedType;
import org.reaktivity.nukleus.amqp.internal.types.codec.AmqpDescribedTypeFW;
import org.reaktivity.nukleus.amqp.internal.types.codec.AmqpEndFW;
import org.reaktivity.nukleus.amqp.internal.types.codec.AmqpErrorListFW;
import org.reaktivity.nukleus.amqp.internal.types.codec.AmqpErrorType;
import org.reaktivity.nukleus.amqp.internal.types.codec.AmqpFlowFW;
import org.reaktivity.nukleus.amqp.internal.types.codec.AmqpFrameHeaderFW;
import org.reaktivity.nukleus.amqp.internal.types.codec.AmqpMapFW;
import org.reaktivity.nukleus.amqp.internal.types.codec.AmqpMessagePropertiesFW;
import org.reaktivity.nukleus.amqp.internal.types.codec.AmqpOpenFW;
import org.reaktivity.nukleus.amqp.internal.types.codec.AmqpPerformativeFW;
import org.reaktivity.nukleus.amqp.internal.types.codec.AmqpPerformativeFW.Builder;
import org.reaktivity.nukleus.amqp.internal.types.codec.AmqpProtocolHeaderFW;
import org.reaktivity.nukleus.amqp.internal.types.codec.AmqpReceiverSettleMode;
import org.reaktivity.nukleus.amqp.internal.types.codec.AmqpRole;
import org.reaktivity.nukleus.amqp.internal.types.codec.AmqpSenderSettleMode;
import org.reaktivity.nukleus.amqp.internal.types.codec.AmqpSourceListFW;
import org.reaktivity.nukleus.amqp.internal.types.codec.AmqpStringFW;
import org.reaktivity.nukleus.amqp.internal.types.codec.AmqpSymbolFW;
import org.reaktivity.nukleus.amqp.internal.types.codec.AmqpTargetListFW;
import org.reaktivity.nukleus.amqp.internal.types.codec.AmqpTransferFW;
import org.reaktivity.nukleus.amqp.internal.types.codec.AmqpType;
import org.reaktivity.nukleus.amqp.internal.types.codec.AmqpULongFW;
import org.reaktivity.nukleus.amqp.internal.types.codec.AmqpValueFW;
import org.reaktivity.nukleus.amqp.internal.types.codec.AmqpValueHeaderFW;
import org.reaktivity.nukleus.amqp.internal.types.control.AmqpRouteExFW;
import org.reaktivity.nukleus.amqp.internal.types.control.RouteFW;
import org.reaktivity.nukleus.amqp.internal.types.stream.AbortFW;
import org.reaktivity.nukleus.amqp.internal.types.stream.AmqpBeginExFW;
import org.reaktivity.nukleus.amqp.internal.types.stream.AmqpDataExFW;
import org.reaktivity.nukleus.amqp.internal.types.stream.BeginFW;
import org.reaktivity.nukleus.amqp.internal.types.stream.DataFW;
import org.reaktivity.nukleus.amqp.internal.types.stream.EndFW;
import org.reaktivity.nukleus.amqp.internal.types.stream.ResetFW;
import org.reaktivity.nukleus.amqp.internal.types.stream.SignalFW;
import org.reaktivity.nukleus.amqp.internal.types.stream.WindowFW;
import org.reaktivity.nukleus.budget.BudgetCreditor;
import org.reaktivity.nukleus.buffer.BufferPool;
import org.reaktivity.nukleus.function.MessageConsumer;
import org.reaktivity.nukleus.function.MessageFunction;
import org.reaktivity.nukleus.function.MessagePredicate;
import org.reaktivity.nukleus.route.RouteManager;
import org.reaktivity.nukleus.stream.StreamFactory;

public final class AmqpServerFactory implements StreamFactory
{
    private static final OctetsFW EMPTY_OCTETS = new OctetsFW().wrap(new UnsafeBuffer(), 0, 0);

    private static final int FRAME_HEADER_SIZE = 11;
    private static final int DESCRIBED_TYPE_SIZE = 3;
    private static final long PROTOCOL_HEADER = 0x414D5150_00010000L;

    private final RouteFW routeRO = new RouteFW();

    private final BeginFW beginRO = new BeginFW();
    private final DataFW dataRO = new DataFW();
    private final EndFW endRO = new EndFW();
    private final AbortFW abortRO = new AbortFW();
    private final WindowFW windowRO = new WindowFW();
    private final ResetFW resetRO = new ResetFW();
    private final SignalFW signalRO = new SignalFW();

    private final BeginFW.Builder beginRW = new BeginFW.Builder();
    private final DataFW.Builder dataRW = new DataFW.Builder();
    private final EndFW.Builder endRW = new EndFW.Builder();
    private final AbortFW.Builder abortRW = new AbortFW.Builder();
    private final WindowFW.Builder windowRW = new WindowFW.Builder();
    private final ResetFW.Builder resetRW = new ResetFW.Builder();
    private final SignalFW.Builder signalRW = new SignalFW.Builder();

    private final AmqpBeginExFW amqpBeginExRO = new AmqpBeginExFW();
    private final AmqpDataExFW amqpDataExRO = new AmqpDataExFW();

    private final AmqpBeginExFW.Builder amqpBeginExRW = new AmqpBeginExFW.Builder();
    private final AmqpDataExFW.Builder amqpDataExRW = new AmqpDataExFW.Builder();

    private final OctetsFW payloadRO = new OctetsFW();
    private final OctetsFW.Builder payloadRW = new OctetsFW.Builder();

    private final AmqpProtocolHeaderFW amqpProtocolHeaderRO = new AmqpProtocolHeaderFW();
    private final AmqpFrameHeaderFW amqpFrameHeaderRO = new AmqpFrameHeaderFW();
    private final AmqpOpenFW amqpOpenRO = new AmqpOpenFW();
    private final AmqpBeginFW amqpBeginRO = new AmqpBeginFW();
    private final AmqpAttachFW amqpAttachRO = new AmqpAttachFW();
    private final AmqpFlowFW amqpFlowRO = new AmqpFlowFW();
    private final AmqpTransferFW amqpTransferRO = new AmqpTransferFW();
    private final AmqpCloseFW amqpCloseRO = new AmqpCloseFW();
    private final AmqpEndFW amqpEndRO = new AmqpEndFW();
    private final AmqpRouteExFW routeExRO = new AmqpRouteExFW();

    private final AmqpProtocolHeaderFW.Builder amqpProtocolHeaderRW = new AmqpProtocolHeaderFW.Builder();
    private final AmqpFrameHeaderFW.Builder amqpFrameHeaderRW = new AmqpFrameHeaderFW.Builder();
    private final AmqpOpenFW.Builder amqpOpenRW = new AmqpOpenFW.Builder();
    private final AmqpBeginFW.Builder amqpBeginRW = new AmqpBeginFW.Builder();
    private final AmqpAttachFW.Builder amqpAttachRW = new AmqpAttachFW.Builder();
    private final AmqpFlowFW.Builder amqpFlowRW = new AmqpFlowFW.Builder();
    private final AmqpTransferFW.Builder amqpTransferRW = new AmqpTransferFW.Builder();
    private final AmqpCloseFW.Builder amqpCloseRW = new AmqpCloseFW.Builder();
    private final AmqpEndFW.Builder amqpEndRW = new AmqpEndFW.Builder();
    private final AmqpErrorListFW.Builder amqpErrorListRW = new AmqpErrorListFW.Builder();
    private final AmqpValueHeaderFW.Builder amqpValueHeaderRW = new AmqpValueHeaderFW.Builder();
    private final AmqpStringFW.Builder amqpStringRW = new AmqpStringFW.Builder();
    private final AmqpStringFW.Builder amqpValueRW = new AmqpStringFW.Builder();
    private final AmqpSymbolFW.Builder amqpSymbolRW = new AmqpSymbolFW.Builder();
    private final AmqpSourceListFW.Builder amqpSourceListRW = new AmqpSourceListFW.Builder();
    private final AmqpTargetListFW.Builder amqpTargetListRW = new AmqpTargetListFW.Builder();
    private final AmqpBinaryFW.Builder amqpBinaryRW = new AmqpBinaryFW.Builder();
    private final AmqpByteFW.Builder amqpByteRW = new AmqpByteFW.Builder();
    private final AmqpMapFW.Builder<AmqpValueFW, AmqpValueFW, AmqpValueFW.Builder, AmqpValueFW.Builder> annotationRW =
        new AmqpMapFW.Builder<>(new AmqpValueFW(), new AmqpValueFW(), new AmqpValueFW.Builder(), new AmqpValueFW.Builder());
    private final AmqpMapFW.Builder<AmqpValueFW, AmqpValueFW, AmqpValueFW.Builder, AmqpValueFW.Builder> applicationPropertyRW =
        new AmqpMapFW.Builder<>(new AmqpValueFW(), new AmqpValueFW(), new AmqpValueFW.Builder(), new AmqpValueFW.Builder());
    private final AmqpULongFW.Builder amqpULongRW = new AmqpULongFW.Builder();
    private final AmqpDescribedTypeFW.Builder amqpDescribedTypeRW = new AmqpDescribedTypeFW.Builder();
    private final AmqpMessagePropertiesFW.Builder amqpPropertiesRW = new AmqpMessagePropertiesFW.Builder();

    private final MutableInteger minimum = new MutableInteger(Integer.MAX_VALUE);
    private final MutableInteger valueOffset = new MutableInteger();

    private final RouteManager router;
    private final MutableDirectBuffer writeBuffer;
    private final MutableDirectBuffer frameBuffer;
    private final MutableDirectBuffer extraBuffer;
    private final MutableDirectBuffer valueBuffer;
    private final MutableDirectBuffer stringBuffer;
    private final LongUnaryOperator supplyInitialId;
    private final LongUnaryOperator supplyReplyId;
    private final LongSupplier supplyTraceId;
    private final LongSupplier supplyBudgetId;

    private final Long2ObjectHashMap<MessageConsumer> correlations;
    private final MessageFunction<RouteFW> wrapRoute = (t, b, i, l) -> routeRO.wrap(b, i, i + l);
    private final int amqpTypeId;

    private final BufferPool bufferPool;
    private final BudgetCreditor creditor;

    private final AmqpServerDecoder decodeFrameType = this::decodeFrameType;
    private final AmqpServerDecoder decodeHeader = this::decodeHeader;
    private final AmqpServerDecoder decodeOpen = this::decodeOpen;
    private final AmqpServerDecoder decodeBegin = this::decodeBegin;
    private final AmqpServerDecoder decodeAttach = this::decodeAttach;
    private final AmqpServerDecoder decodeFlow = this::decodeFlow;
    private final AmqpServerDecoder decodeClose = this::decodeClose;
    private final AmqpServerDecoder decodeIgnoreAll = this::decodeIgnoreAll;
    private final AmqpServerDecoder decodeUnknownType = this::decodeUnknownType;

    private final int outgoingWindow;
    private final String containerId;
    private final long defaultMaxFrameSize;
    private final long initialDeliveryCount;

    private final Map<AmqpDescribedType, AmqpServerDecoder> decodersByPerformative;
    {
        final Map<AmqpDescribedType, AmqpServerDecoder> decodersByPerformative = new EnumMap<>(AmqpDescribedType.class);
        decodersByPerformative.put(AmqpDescribedType.OPEN, decodeOpen);
        decodersByPerformative.put(AmqpDescribedType.BEGIN, decodeBegin);
        decodersByPerformative.put(AmqpDescribedType.CLOSE, decodeClose);
        decodersByPerformative.put(AmqpDescribedType.ATTACH, decodeAttach);
        decodersByPerformative.put(AmqpDescribedType.FLOW, decodeFlow);
        // decodersByFrameType.put(AmqpFrameType.TRANSFER, decodeTransfer);
        // decodersByFrameType.put(AmqpFrameType.DISPOSITION, decodeDisposition);
        // decodersByFrameType.put(AmqpFrameType.DETACH, decodeDetach);
        // decodersByFrameType.put(AmqpFrameType.END, decodeEnd);
        this.decodersByPerformative = decodersByPerformative;
    }

    public AmqpServerFactory(
        AmqpConfiguration config,
        RouteManager router,
        MutableDirectBuffer writeBuffer,
        BufferPool bufferPool,
        BudgetCreditor creditor,
        LongUnaryOperator supplyInitialId,
        LongUnaryOperator supplyReplyId,
        LongSupplier supplyBudgetId,
        LongSupplier supplyTraceId,
        ToIntFunction<String> supplyTypeId)
    {
        this.router = requireNonNull(router);
        this.writeBuffer = requireNonNull(writeBuffer);
        this.frameBuffer = new UnsafeBuffer(new byte[writeBuffer.capacity()]);
        this.extraBuffer = new UnsafeBuffer(new byte[writeBuffer.capacity()]);
        this.stringBuffer = new UnsafeBuffer(new byte[writeBuffer.capacity()]);
        this.valueBuffer = new UnsafeBuffer(new byte[writeBuffer.capacity()]);
        this.bufferPool = bufferPool;
        this.creditor = creditor;
        this.supplyInitialId = requireNonNull(supplyInitialId);
        this.supplyReplyId = requireNonNull(supplyReplyId);
        this.supplyBudgetId = requireNonNull(supplyBudgetId);
        this.supplyTraceId = requireNonNull(supplyTraceId);
        this.correlations = new Long2ObjectHashMap<>();
        this.amqpTypeId = supplyTypeId.applyAsInt(AmqpNukleus.NAME);
        this.containerId = config.containerId();
        this.outgoingWindow = config.outgoingWindow();
        this.defaultMaxFrameSize = config.maxFrameSize();
        this.initialDeliveryCount = config.initialDeliveryCount();
    }

    @Override
    public MessageConsumer newStream(
        int msgTypeId,
        DirectBuffer buffer,
        int index,
        int length,
        MessageConsumer throttle)
    {
        final BeginFW begin = beginRO.wrap(buffer, index, index + length);
        final long streamId = begin.streamId();

        MessageConsumer newStream = null;

        if ((streamId & 0x0000_0000_0000_0001L) != 0L)
        {
            newStream = newInitialStream(begin, throttle);
        }
        else
        {
            newStream = newReplyStream(begin, throttle);
        }
        return newStream;
    }

    private MessageConsumer newInitialStream(
        final BeginFW begin,
        final MessageConsumer sender)
    {
        final long routeId = begin.routeId();

        final MessagePredicate filter = (t, b, o, l) -> true;
        final RouteFW route = router.resolve(routeId, begin.authorization(), filter, wrapRoute);
        MessageConsumer newStream = null;

        if (route != null)
        {
            final long initialId = begin.streamId();
            final long affinity = begin.affinity();

            newStream = new AmqpServer(sender, routeId, initialId, affinity)::onNetwork;
        }
        return newStream;
    }

    private MessageConsumer newReplyStream(
        final BeginFW begin,
        final MessageConsumer sender)
    {
        final long replyId = begin.streamId();
        return correlations.remove(replyId);
    }

    private RouteFW resolveTarget(
        long routeId,
        long authorization,
        StringFW targetAddress,
        AmqpCapabilities capabilities)
    {
        final MessagePredicate filter = (t, b, o, l) ->
        {
            final RouteFW route = routeRO.wrap(b, o, o + l);
            final OctetsFW extension = route.extension();
            final AmqpRouteExFW routeEx = extension.get(routeExRO::tryWrap);
            return routeEx == null || Objects.equals(targetAddress, routeEx.targetAddress()) &&
                    capabilities == routeEx.capabilities().get();
        };
        return router.resolve(routeId, authorization, filter, wrapRoute);
    }

    private void doBegin(
        MessageConsumer receiver,
        long routeId,
        long replyId,
        long traceId,
        long authorization,
        long affinity,
        Flyweight extension)
    {
        final BeginFW begin = beginRW.wrap(writeBuffer, 0, writeBuffer.capacity())
            .routeId(routeId)
            .streamId(replyId)
            .traceId(traceId)
            .authorization(authorization)
            .affinity(affinity)
            .extension(extension.buffer(), extension.offset(), extension.sizeof())
            .build();

        receiver.accept(begin.typeId(), begin.buffer(), begin.offset(), begin.sizeof());
    }

    private void doData(
        MessageConsumer receiver,
        long routeId,
        long replyId,
        long traceId,
        long authorization,
        long budgetId,
        int reserved,
        DirectBuffer buffer,
        int index,
        int length,
        Flyweight extension)
    {
        final DataFW data = dataRW.wrap(writeBuffer, 0, writeBuffer.capacity())
            .routeId(routeId)
            .streamId(replyId)
            .traceId(traceId)
            .authorization(authorization)
            .budgetId(budgetId)
            .reserved(reserved)
            .payload(buffer, index, length)
            .extension(extension.buffer(), extension.offset(), extension.sizeof())
            .build();

        receiver.accept(data.typeId(), data.buffer(), data.offset(), data.sizeof());
    }

    private void doEnd(
        MessageConsumer receiver,
        long routeId,
        long replyId,
        long traceId,
        long authorization,
        Flyweight extension)
    {
        final EndFW end = endRW.wrap(writeBuffer, 0, writeBuffer.capacity())
            .routeId(routeId)
            .streamId(replyId)
            .traceId(traceId)
            .authorization(authorization)
            .extension(extension.buffer(), extension.offset(), extension.sizeof())
            .build();

        receiver.accept(end.typeId(), end.buffer(), end.offset(), end.sizeof());
    }

    private void doAbort(
        MessageConsumer receiver,
        long routeId,
        long replyId,
        long traceId,
        long authorization,
        Flyweight extension)
    {
        final AbortFW abort = abortRW.wrap(writeBuffer, 0, writeBuffer.capacity())
            .routeId(routeId)
            .streamId(replyId)
            .traceId(traceId)
            .authorization(authorization)
            .extension(extension.buffer(), extension.offset(), extension.sizeof())
            .build();

        receiver.accept(abort.typeId(), abort.buffer(), abort.offset(), abort.sizeof());
    }

    private void doWindow(
        MessageConsumer receiver,
        long routeId,
        long streamId,
        long traceId,
        long authorization,
        long budgetId,
        int credit,
        int padding,
        int min)
    {
        final WindowFW window = windowRW.wrap(writeBuffer, 0, writeBuffer.capacity())
            .routeId(routeId)
            .streamId(streamId)
            .traceId(traceId)
            .authorization(authorization)
            .budgetId(budgetId)
            .credit(credit)
            .padding(padding)
            .minimum(min)
            .build();

        receiver.accept(window.typeId(), window.buffer(), window.offset(), window.sizeof());
    }

    private void doReset(
        MessageConsumer receiver,
        long routeId,
        long streamId,
        long traceId,
        long authorization,
        Flyweight extension)
    {
        final ResetFW reset = resetRW.wrap(writeBuffer, 0, writeBuffer.capacity()).routeId(routeId)
            .streamId(streamId)
            .traceId(traceId)
            .authorization(authorization)
            .extension(extension.buffer(), extension.offset(), extension.sizeof())
            .build();

        receiver.accept(reset.typeId(), reset.buffer(), reset.offset(), reset.sizeof());
    }

    private void doSignal(
        MessageConsumer receiver,
        long routeId,
        long streamId,
        long traceId)
    {
        final SignalFW signal = signalRW.wrap(writeBuffer, 0, writeBuffer.capacity()).routeId(routeId)
            .streamId(streamId)
            .traceId(traceId)
            .build();

        receiver.accept(signal.typeId(), signal.buffer(), signal.offset(), signal.sizeof());
    }

    @FunctionalInterface
    private interface AmqpServerDecoder
    {
        int decode(
            AmqpServer server,
            long traceId,
            long authorization,
            long budgetId,
            DirectBuffer buffer,
            int offset,
            int limit);
    }

    private int decodeFrameType(
        AmqpServer server,
        final long traceId,
        final long authorization,
        final long budgetId,
        final DirectBuffer buffer,
        final int offset,
        final int limit)
    {
        int progress = offset;
        final AmqpFrameHeaderFW frameHeader = amqpFrameHeaderRO.tryWrap(buffer, offset, limit);

        if (frameHeader != null)
        {
            final AmqpPerformativeFW performative = frameHeader.performative();
            final AmqpDescribedType frameType = performative.kind();
            final AmqpServerDecoder decoder = decodersByPerformative.getOrDefault(frameType, decodeUnknownType);
            final ListFW frame;
            switch (frameType)
            {
            case OPEN:
                frame = performative.open();
                server.onDecodeFrameHeader(frameHeader, b -> b.open(performative.open()));
                break;
            case BEGIN:
                frame = performative.begin();
                server.onDecodeFrameHeader(frameHeader, b -> b.begin(performative.begin()));
                break;
            case ATTACH:
                frame = performative.attach();
                server.onDecodeFrameHeader(frameHeader, b -> b.attach(performative.attach()));
                break;
            case FLOW:
                frame = performative.flow();
                server.onDecodeFrameHeader(frameHeader, b -> b.flow(performative.flow()));
                break;
            case TRANSFER:
                frame = performative.transfer();
                server.onDecodeFrameHeader(frameHeader, b -> b.transfer(performative.transfer()));
                break;
            case DISPOSITION:
                frame = performative.disposition();
                server.onDecodeFrameHeader(frameHeader, b -> b.disposition(performative.disposition()));
                break;
            case DETACH:
                frame = performative.detach();
                server.onDecodeFrameHeader(frameHeader, b -> b.detach(performative.detach()));
                break;
            case END:
                frame = performative.end();
                server.onDecodeFrameHeader(frameHeader, b -> b.end(performative.end()));
                break;
            case CLOSE:
                frame = performative.close();
                server.onDecodeFrameHeader(frameHeader, b -> b.close(performative.close()));
                break;
            default:
                throw new IllegalStateException("Unexpected value: " + frameType);
            }
            server.decoder = decoder;
            progress = frame.offset();
        }

        return progress;
    }

    private int decodeHeader(
        AmqpServer server,
        final long traceId,
        final long authorization,
        final long budgetId,
        final DirectBuffer buffer,
        final int offset,
        final int limit)
    {
        final AmqpProtocolHeaderFW protocolHeader = amqpProtocolHeaderRO.tryWrap(buffer, offset, limit);

        int progress = offset;
        if (protocolHeader != null)
        {
            server.onDecodeHeader(traceId, authorization, protocolHeader);
            server.decoder = decodeFrameType;
            progress = protocolHeader.limit();
        }

        return progress;
    }

    private int decodeOpen(
        AmqpServer server,
        final long traceId,
        final long authorization,
        final long budgetId,
        final DirectBuffer buffer,
        final int offset,
        final int limit)
    {
        AmqpOpenFW open = amqpOpenRO.tryWrap(buffer, offset, limit);

        int progress = offset;

        if (open != null)
        {
            server.onDecodeOpen(traceId, authorization, open);
            server.decoder = decodeFrameType;
            progress = open.limit();
        }
        else
        {
            server.decoder = decodeIgnoreAll;
        }
        return progress;
    }

    private int decodeBegin(
        AmqpServer server,
        final long traceId,
        final long authorization,
        final long budgetId,
        final DirectBuffer buffer,
        final int offset,
        final int limit)
    {
        AmqpBeginFW begin = amqpBeginRO.tryWrap(buffer, offset, limit);

        int progress = offset;

        if (begin != null)
        {
            server.onDecodeBegin(traceId, authorization, begin);
            server.decoder = decodeFrameType;
            progress = begin.limit();
        }
        return progress;
    }

    private int decodeAttach(
        AmqpServer server,
        final long traceId,
        final long authorization,
        final long budgetId,
        final DirectBuffer buffer,
        final int offset,
        final int limit)
    {
        final AmqpAttachFW attach = amqpAttachRO.tryWrap(buffer, offset, limit);

        int progress = offset;

        if (attach != null)
        {
            server.onDecodeAttach(traceId, authorization, attach, amqpFrameHeaderRO.channel());
            server.decoder = decodeFrameType;
            progress = attach.limit();
        }
        return progress;
    }

    private int decodeFlow(
        AmqpServer server,
        final long traceId,
        final long authorization,
        final long budgetId,
        final DirectBuffer buffer,
        final int offset,
        final int limit)
    {
        final AmqpFlowFW flow = amqpFlowRO.tryWrap(buffer, offset, limit);

        int progress = offset;

        if (flow != null)
        {
            server.onDecodeFlow(traceId, authorization, flow);
            server.decoder = decodeFrameType;
            progress = flow.limit();
        }
        return progress;
    }

    private int decodeClose(
        AmqpServer server,
        final long traceId,
        final long authorization,
        final long budgetId,
        final DirectBuffer buffer,
        final int offset,
        final int limit)
    {
        final AmqpCloseFW close = amqpCloseRO.tryWrap(buffer, offset, limit);

        int progress = offset;

        if (close != null)
        {
            server.onDecodeClose(traceId, authorization, close);
            server.decoder = decodeFrameType;
            progress = close.limit();
        }
        return progress;
    }

    private int decodeIgnoreAll(
        AmqpServer server,
        long traceId,
        long authorization,
        long budgetId,
        DirectBuffer buffer,
        int offset,
        int limit)
    {
        return limit;
    }

    private int decodeUnknownType(
        AmqpServer server,
        final long traceId,
        final long authorization,
        final long budgetId,
        final DirectBuffer buffer,
        final int offset,
        final int limit)
    {
        server.onDecodeError(traceId, authorization, DECODE_ERROR);
        server.decoder = decodeIgnoreAll;
        return limit;
    }

    private final class AmqpServer
    {
        private final MessageConsumer network;
        private final long routeId;
        private final long initialId;
        private final long replyId;
        private final long affinity;
        private final long budgetId;
        private final long replySharedBudgetId;

        private final Int2ObjectHashMap<AmqpSession> sessions;

        private int initialBudget;
        private int initialPadding;
        private int replyBudget;
        private int replyPadding;

        private long replyBudgetIndex = NO_CREDITOR_INDEX;
        private int replySharedBudget;

        private int decodeSlot = NO_SLOT;
        private int decodeSlotLimit;

        private int encodeSlot = NO_SLOT;
        private int encodeSlotOffset;
        private long encodeSlotTraceId;
        private int encodeSlotMaxLimit = Integer.MAX_VALUE;

        private int channelId;
        private int initialMaxFrameSize;

        private AmqpServerDecoder decoder;

        private int state;

        private AmqpServer(
            MessageConsumer network,
            long routeId,
            long initialId,
            long affinity)
        {
            this.network = network;
            this.routeId = routeId;
            this.initialId = initialId;
            this.replyId = supplyReplyId.applyAsLong(initialId);
            this.budgetId = supplyBudgetId.getAsLong();
            this.affinity = affinity;
            this.replySharedBudgetId = budgetId;
            this.decoder = decodeHeader;
            this.sessions = new Int2ObjectHashMap<>();
        }

        private void doEncodeProtocolHeader(
            int major,
            int minor,
            int revision,
            long traceId,
            long authorization)
        {
            final AmqpProtocolHeaderFW protocolHeader = amqpProtocolHeaderRW
                .wrap(writeBuffer, DataFW.FIELD_OFFSET_PAYLOAD, writeBuffer.capacity())
                .name(n -> n.set("AMQP".getBytes(StandardCharsets.US_ASCII)))
                .id(0)
                .major(major)
                .minor(minor)
                .revision(revision)
                .build();

            doNetworkData(traceId, authorization, 0L, protocolHeader);
        }

        private void doEncodeOpen(
            long traceId,
            long authorization,
            boolean hasMaxFrameSize)
        {
            int channel = amqpFrameHeaderRW.build().channel();
            final StringFW containerIdRO = amqpStringRW.wrap(stringBuffer, 0, stringBuffer.capacity())
                .set(containerId, UTF_8)
                .build()
                .get();

            amqpOpenRW.wrap(frameBuffer, FRAME_HEADER_SIZE, frameBuffer.capacity())
                .containerId(containerIdRO);

            if (hasMaxFrameSize)
            {
                amqpOpenRW.maxFrameSize(defaultMaxFrameSize);
            }

            final AmqpOpenFW open = amqpOpenRW.build();

            final AmqpFrameHeaderFW updatedFrameHeader =
                amqpFrameHeaderRW.wrap(frameBuffer, 0, frameBuffer.capacity())
                    .size(FRAME_HEADER_SIZE + open.sizeof())
                    .doff(2)
                    .type(0)
                    .channel(channel)
                    .performative(b -> b.open(open))
                    .build();

            OctetsFW openWithFrameHeader = payloadRW.wrap(writeBuffer, DataFW.FIELD_OFFSET_PAYLOAD, writeBuffer.capacity())
                .put(updatedFrameHeader.buffer(), 0, updatedFrameHeader.sizeof())
                .build();

            doNetworkData(traceId, authorization, 0L, openWithFrameHeader);
        }

        private void doEncodeBegin(
            long traceId,
            long authorization,
            int channel,
            int nextOutgoingId)
        {
            final AmqpBeginFW begin = amqpBeginRW.wrap(frameBuffer, FRAME_HEADER_SIZE, frameBuffer.capacity())
                .remoteChannel(channel)
                .nextOutgoingId(nextOutgoingId)
                .incomingWindow(bufferPool.slotCapacity())
                .outgoingWindow(outgoingWindow)
                .build();

            final AmqpFrameHeaderFW frameHeader =
                amqpFrameHeaderRW.wrap(frameBuffer, 0, frameBuffer.capacity())
                    .size(FRAME_HEADER_SIZE + begin.sizeof())
                    .doff(2)
                    .type(0)
                    .channel(channel)
                    .performative(b -> b.begin(begin))
                    .build();

            OctetsFW beginWithFrameHeader = payloadRW.wrap(writeBuffer, DataFW.FIELD_OFFSET_PAYLOAD, writeBuffer.capacity())
                .put(frameHeader.buffer(), frameHeader.offset(), frameHeader.sizeof())
                .build();

            doNetworkData(traceId, authorization, 0L, beginWithFrameHeader);
        }

        private void doEncodeAttach(
            long traceId,
            long authorization,
            String name,
            int channel,
            long handle,
            AmqpRole role,
            AmqpSenderSettleMode senderSettleMode,
            AmqpReceiverSettleMode receiverSettleMode,
            String address)
        {
            AmqpAttachFW.Builder attachRW = amqpAttachRW.wrap(frameBuffer, FRAME_HEADER_SIZE, frameBuffer.capacity())
                .name(amqpStringRW.wrap(stringBuffer, 0, stringBuffer.capacity()).set(name, UTF_8).build().get())
                .handle(handle)
                .role(role)
                .sndSettleMode(senderSettleMode)
                .rcvSettleMode(receiverSettleMode);

            final StringFW addressRO = amqpStringRW.wrap(stringBuffer, 0, stringBuffer.capacity())
                .set(address, UTF_8)
                .build()
                .get();
            switch (role)
            {
            case SENDER:
                AmqpSourceListFW sourceList = amqpSourceListRW
                    .wrap(extraBuffer, 0, extraBuffer.capacity())
                    .address(addressRO)
                    .build();
                AmqpTargetListFW emptyList = amqpTargetListRW
                    .wrap(extraBuffer, sourceList.limit(), extraBuffer.capacity())
                    .build();

                attachRW.source(b -> b.sourceList(sourceList))
                    .target(b -> b.targetList(emptyList))
                    .initialDeliveryCount(initialDeliveryCount);
                break;
            case RECEIVER:
                AmqpTargetListFW targetList = amqpTargetListRW
                    .wrap(extraBuffer, 0, extraBuffer.capacity())
                    .address(addressRO)
                    .build();
                attachRW.target(b -> b.targetList(targetList));
                break;
            }

            final AmqpAttachFW attach = attachRW.build();

            final AmqpFrameHeaderFW frameHeader = amqpFrameHeaderRW.wrap(frameBuffer, 0, frameBuffer.capacity())
                .size(FRAME_HEADER_SIZE + attach.sizeof())
                .doff(2)
                .type(0)
                .channel(channel)
                .performative(b -> b.attach(attach))
                .build();

            OctetsFW attachWithFrameHeader = payloadRW.wrap(writeBuffer, DataFW.FIELD_OFFSET_PAYLOAD, writeBuffer.capacity())
                .put(frameHeader.buffer(), frameHeader.offset(), frameHeader.sizeof())
                .build();

            doNetworkData(traceId, authorization, 0L, attachWithFrameHeader);
        }

        private void doEncodeTransfer(
            long traceId,
            long authorization,
            int channel,
            int remoteIncomingWindow,
            long handle,
            int flags,
            OctetsFW extension,
            OctetsFW payload)
        {
            final AmqpDataExFW dataEx = extension.get(amqpDataExRO::tryWrap);
            int deferred = dataEx != null ? dataEx.deferred() : 0;
            int extraBufferOffset = 0;
            int flag = dataEx != null ? dataEx.flags() : 0;
            int bitmask = 1;
            int settled = 0;
            if ((flag & bitmask) == bitmask)
            {
                settled = 1;
                bitmask = bitmask << 2;
            }
            // TODO: add cases for resume, aborted, batchable

            final AmqpMapFW<AmqpValueFW, AmqpValueFW> annotations = dataEx != null ?
                annotations(dataEx.annotations(), extraBufferOffset) : null;
            extraBufferOffset = annotations == null ? extraBufferOffset : annotations.limit();
            final AmqpMessagePropertiesFW properties = dataEx != null ? properties(dataEx, extraBufferOffset) : null;
            extraBufferOffset = properties == null ? extraBufferOffset : properties.limit();
            final AmqpMapFW<AmqpValueFW, AmqpValueFW> applicationProperties = dataEx != null ?
                applicationProperties(dataEx.applicationProperties(), extraBufferOffset) : null;
            extraBufferOffset = applicationProperties == null ? extraBufferOffset : applicationProperties.limit();
            int payloadIndex = 0;
            int payloadSize = payload.sizeof();
            int originalPayloadSize = payloadSize;
            AmqpValueHeaderFW valueHeader = amqpValueHeaderRW.wrap(extraBuffer, extraBufferOffset, extraBuffer.capacity())
                .sectionType(b -> b.set(AmqpDescribedType.VALUE))
                .valueType(b -> b.set(AmqpType.BINARY4))
                .valueLength(deferred == 0 ? originalPayloadSize : originalPayloadSize + deferred)
                .build();
            extraBufferOffset = valueHeader.limit();
            final BoundedOctetsFW deliveryTag = dataEx != null ?
                amqpBinaryRW.wrap(extraBuffer, extraBufferOffset, extraBuffer.capacity())
                    .set(dataEx.deliveryTag().bytes().value(), 0, dataEx.deliveryTag().length())
                    .build()
                    .get() : null;
            final int valueHeaderSize = flags > 1 ? valueHeader.sizeof() : 0;
            final int annotationsSize = annotations == null ? 0 : DESCRIBED_TYPE_SIZE + annotations.sizeof();
            final int propertiesSize = properties == null ? 0 : DESCRIBED_TYPE_SIZE + properties.sizeof();
            final int applicationPropertiesSize = applicationProperties == null ? 0 :
                DESCRIBED_TYPE_SIZE + applicationProperties.sizeof();
            while (payloadIndex < originalPayloadSize && remoteIncomingWindow >= 0)
            {
                AmqpTransferFW.Builder transferBuilder = amqpTransferRW.wrap(frameBuffer, FRAME_HEADER_SIZE,
                    frameBuffer.capacity());
                AmqpTransferFW transfer = payloadIndex > 0 || flags == 1 ? transferBuilder.handle(handle).build() :
                    flags == 0 ? transferBuilder.handle(handle).more(1).build() :
                        transferBuilder.handle(handle)
                            .deliveryId(dataEx.deliveryId())
                            .deliveryTag(deliveryTag)
                            .messageFormat(dataEx.messageFormat())
                            .settled(settled)
                            .build();
                final int transferFrameSize = transfer.sizeof() + Byte.BYTES;

                payloadRW.wrap(writeBuffer, DataFW.FIELD_OFFSET_PAYLOAD, writeBuffer.capacity());
                if (payloadIndex == 0)
                {
                    if (FRAME_HEADER_SIZE + transferFrameSize + annotationsSize + propertiesSize + applicationPropertiesSize +
                        valueHeaderSize + payloadSize > initialMaxFrameSize || flags == 2)
                    {
                        transfer = amqpTransferRW.wrap(frameBuffer, FRAME_HEADER_SIZE, frameBuffer.capacity())
                            .handle(handle)
                            .deliveryId(dataEx.deliveryId())
                            .deliveryTag(deliveryTag)
                            .messageFormat(dataEx.messageFormat())
                            .settled(settled)
                            .more(1)
                            .build();
                        if (flags == 2)
                        {
                            int padding = FRAME_HEADER_SIZE + transferFrameSize + annotationsSize + propertiesSize +
                                applicationPropertiesSize + valueHeaderSize;
                            if (padding + payloadSize > initialMaxFrameSize)
                            {
                                // TODO: buffer?
                            }
                        }
                        else
                        {
                            payloadSize = initialMaxFrameSize - FRAME_HEADER_SIZE - transferFrameSize - annotationsSize -
                                propertiesSize - applicationPropertiesSize - valueHeaderSize;
                        }
                    }
                    AmqpFrameHeaderFW transferFrame = setTransferFrame(transfer, FRAME_HEADER_SIZE + transfer.sizeof() +
                        annotationsSize + propertiesSize + applicationPropertiesSize + valueHeaderSize + payloadSize, channel);
                    int sectionOffset = transferFrame.limit();
                    amqpSection(annotations, properties, applicationProperties, sectionOffset);
                    if (flags > 1)
                    {
                        payloadRW.put(valueHeader.buffer(), valueHeader.offset(), valueHeader.sizeof());
                    }
                }
                else
                {
                    if (payloadIndex + (initialMaxFrameSize - FRAME_HEADER_SIZE - transferFrameSize) > originalPayloadSize)
                    {
                        payloadSize = originalPayloadSize - payloadIndex;
                    }
                    else
                    {
                        transfer = amqpTransferRW.wrap(frameBuffer, FRAME_HEADER_SIZE, frameBuffer.capacity())
                            .handle(handle)
                            .deliveryId(dataEx.deliveryId())
                            .deliveryTag(deliveryTag)
                            .messageFormat(dataEx.messageFormat())
                            .settled(settled)
                            .more(1)
                            .build();
                        payloadSize = initialMaxFrameSize - FRAME_HEADER_SIZE - transferFrameSize;
                    }
                    setTransferFrame(transfer, FRAME_HEADER_SIZE + transfer.sizeof() + payloadSize, channel);
                }
                payloadRW.put(payload.buffer(), payload.offset() + payloadIndex, payloadSize);
                OctetsFW transferWithFrameHeader = payloadRW.build();
                doNetworkData(traceId, authorization, 0L, transferWithFrameHeader);
                payloadIndex += payloadSize;
                remoteIncomingWindow--;
            }
        }

        private void amqpSection(
            AmqpMapFW<AmqpValueFW, AmqpValueFW> annotations,
            AmqpMessagePropertiesFW properties,
            AmqpMapFW<AmqpValueFW, AmqpValueFW> applicationProperties,
            int sectionOffset)
        {
            if (annotations != null)
            {
                AmqpDescribedTypeFW messageAnnotationsType = amqpDescribedTypeRW.wrap(frameBuffer, sectionOffset,
                    frameBuffer.capacity())
                    .set(MESSAGE_ANNOTATIONS)
                    .build();
                payloadRW.put(messageAnnotationsType.buffer(), messageAnnotationsType.offset(),
                    messageAnnotationsType.sizeof())
                    .put(annotations.buffer(), annotations.offset(), annotations.sizeof());
                sectionOffset = messageAnnotationsType.limit();
            }
            if (properties != null)
            {
                AmqpDescribedTypeFW propertiesType = amqpDescribedTypeRW.wrap(frameBuffer, sectionOffset,
                    frameBuffer.capacity())
                    .set(PROPERTIES)
                    .build();
                payloadRW.put(propertiesType.buffer(), propertiesType.offset(), propertiesType.sizeof())
                    .put(properties.buffer(), properties.offset(), properties.sizeof());
                sectionOffset = propertiesType.limit();
            }
            if (applicationProperties != null)
            {
                AmqpDescribedTypeFW applicationPropertiesType = amqpDescribedTypeRW.wrap(frameBuffer, sectionOffset,
                    frameBuffer.capacity())
                    .set(APPLICATION_PROPERTIES)
                    .build();
                payloadRW.put(applicationPropertiesType.buffer(), applicationPropertiesType.offset(),
                    applicationPropertiesType.sizeof())
                    .put(applicationProperties.buffer(), applicationProperties.offset(), applicationProperties.sizeof());
            }
        }

        private AmqpFrameHeaderFW setTransferFrame(
            AmqpTransferFW transfer,
            long size,
            int channel)
        {
            AmqpFrameHeaderFW frameHeader = amqpFrameHeaderRW.wrap(frameBuffer, 0, frameBuffer.capacity())
                .size(size)
                .doff(2)
                .type(0)
                .channel(channel)
                .performative(b -> b.transfer(transfer))
                .build();
            payloadRW.put(frameHeader.buffer(), frameHeader.offset(), frameHeader.sizeof());
            return frameHeader;
        }

        private AmqpMapFW<AmqpValueFW, AmqpValueFW> annotations(
            ArrayFW<AmqpAnnotationFW> annotations,
            int bufferOffset)
        {
            AmqpMapFW<AmqpValueFW, AmqpValueFW> messageAnnotations = null;
            if (annotations.fieldCount() > 0)
            {
                annotationRW.wrap(extraBuffer, bufferOffset, extraBuffer.capacity());
                annotations.forEach(this::putAnnotation);
                messageAnnotations = annotationRW.build();
            }
            return messageAnnotations;
        }

        private AmqpMessagePropertiesFW properties(
            AmqpDataExFW dataEx,
            int bufferOffset)
        {
            AmqpMessagePropertiesFW properties = null;
            AmqpPropertiesFW propertiesEx = dataEx.properties();
            if (propertiesEx.fieldCount() > 0)
            {
                amqpPropertiesRW.wrap(extraBuffer, bufferOffset, extraBuffer.capacity());
                if (propertiesEx.hasMessageId())
                {
                    amqpPropertiesRW.messageId(propertiesEx.messageId().stringtype());
                }
                if (propertiesEx.hasUserId())
                {
                    final BoundedOctetsFW userId = amqpBinaryRW.wrap(stringBuffer, 0, stringBuffer.capacity())
                        .set(propertiesEx.userId().bytes().value(), 0, propertiesEx.userId().length())
                        .build()
                        .get();
                    amqpPropertiesRW.userId(userId);
                }
                if (propertiesEx.hasTo())
                {
                    amqpPropertiesRW.to(propertiesEx.to());
                }
                if (propertiesEx.hasSubject())
                {
                    amqpPropertiesRW.subject(propertiesEx.subject());
                }
                if (propertiesEx.hasReplyTo())
                {
                    amqpPropertiesRW.replyTo(propertiesEx.replyTo());
                }
                if (propertiesEx.hasCorrelationId())
                {
                    amqpPropertiesRW.correlationId(propertiesEx.correlationId().stringtype());
                }
                if (propertiesEx.hasContentType())
                {
                    amqpPropertiesRW.contentType(propertiesEx.contentType());
                }
                if (propertiesEx.hasContentEncoding())
                {
                    amqpPropertiesRW.contentEncoding(propertiesEx.contentEncoding());
                }
                if (propertiesEx.hasAbsoluteExpiryTime())
                {
                    amqpPropertiesRW.absoluteExpiryTime(propertiesEx.absoluteExpiryTime());
                }
                if (propertiesEx.hasCreationTime())
                {
                    amqpPropertiesRW.creationTime(propertiesEx.creationTime());
                }
                if (propertiesEx.hasGroupId())
                {
                    amqpPropertiesRW.groupId(propertiesEx.groupId());
                }
                if (propertiesEx.hasGroupSequence())
                {
                    amqpPropertiesRW.groupSequence(propertiesEx.groupSequence());
                }
                if (propertiesEx.hasReplyToGroupId())
                {
                    amqpPropertiesRW.replyToGroupId(propertiesEx.replyToGroupId());
                }
                properties = amqpPropertiesRW.build();
            }
            return properties;
        }

        private AmqpMapFW<AmqpValueFW, AmqpValueFW> applicationProperties(
            ArrayFW<AmqpApplicationPropertyFW> applicationProperties,
            int bufferOffset)
        {
            AmqpMapFW<AmqpValueFW, AmqpValueFW> properties = null;
            if (applicationProperties.fieldCount() > 0)
            {
                applicationPropertyRW.wrap(extraBuffer, bufferOffset, extraBuffer.capacity());
                applicationProperties.forEach(this::putProperty);
                properties = applicationPropertyRW.build();
            }
            valueOffset.value = 0;
            return properties;
        }

        private void putAnnotation(
            AmqpAnnotationFW item)
        {
            switch (item.key().kind())
            {
            case 1:
                AmqpULongFW id = amqpULongRW.wrap(valueBuffer, valueOffset.value, valueBuffer.capacity())
                    .set(item.key().id()).build();
                valueOffset.value += id.sizeof();
                AmqpByteFW value1 = amqpByteRW.wrap(valueBuffer, valueOffset.value, valueBuffer.capacity())
                    .set(item.value().bytes().value().getByte(0)).build();
                valueOffset.value += value1.sizeof();
                annotationRW.entry(k -> k.setAsAmqpULong(id), v -> v.setAsAmqpByte(value1));
                break;
            case 2:
                AmqpSymbolFW name = amqpSymbolRW.wrap(valueBuffer, valueOffset.value, valueBuffer.capacity())
                    .set(item.key().name()).build();
                valueOffset.value += name.sizeof();
                AmqpByteFW value2 = amqpByteRW.wrap(valueBuffer, valueOffset.value, valueBuffer.capacity())
                    .set(item.value().bytes().value().getByte(0)).build();
                valueOffset.value += value2.sizeof();
                annotationRW.entry(k -> k.setAsAmqpSymbol(name), v -> v.setAsAmqpByte(value2));
                break;
            }
        }

        private void putProperty(
            AmqpApplicationPropertyFW item)
        {
            AmqpStringFW key = amqpStringRW.wrap(valueBuffer, valueOffset.value, valueBuffer.capacity())
                .set(item.key()).build();
            valueOffset.value += key.sizeof();
            AmqpStringFW value = amqpValueRW.wrap(valueBuffer, valueOffset.value, valueBuffer.capacity())
                .set(item.value()).build();
            valueOffset.value += value.sizeof();
            applicationPropertyRW.entry(k -> k.setAsAmqpString(key), v -> v.setAsAmqpString(value));
        }

        private void doEncodeClose(
            long traceId,
            long authorization,
            AmqpErrorType errorType)
        {
            AmqpFrameHeaderFW originalFrameHeader = amqpFrameHeaderRW.build();
            int offsetCloseFrame = originalFrameHeader.performative().close().offset();

            final AmqpCloseFW.Builder closeRW = amqpCloseRW.wrap(frameBuffer, offsetCloseFrame, frameBuffer.capacity());

            AmqpCloseFW close;
            if (errorType != null)
            {
                AmqpErrorListFW errorList = amqpErrorListRW.wrap(extraBuffer, 0, extraBuffer.capacity())
                    .condition(errorType)
                    .build();
                close = closeRW.error(e -> e.errorList(errorList)).build();
            }
            else
            {
                close = closeRW.build();
            }

            final AmqpFrameHeaderFW updatedFrameHeader =
                amqpFrameHeaderRW.wrap(frameBuffer, 0, frameBuffer.capacity())
                    .size(originalFrameHeader.sizeof() - originalFrameHeader.performative().close().sizeof() + close.sizeof())
                    .doff(originalFrameHeader.doff())
                    .type(originalFrameHeader.type())
                    .channel(originalFrameHeader.channel())
                    .performative(b -> b.close(close))
                    .build();

            OctetsFW closeWithFrameHeader = payloadRW.wrap(writeBuffer, DataFW.FIELD_OFFSET_PAYLOAD, writeBuffer.capacity())
                .put(updatedFrameHeader.buffer(), 0, updatedFrameHeader.sizeof())
                .build();

            doNetworkData(traceId, authorization, 0L, closeWithFrameHeader);
        }

        private void encodeNetwork(
            long traceId,
            long authorization,
            long budgetId,
            DirectBuffer buffer,
            int offset,
            int limit,
            int maxLimit)
        {
            encodeNetworkData(traceId, authorization, budgetId, buffer, offset, limit, maxLimit);
        }

        private void encodeNetworkData(
            long traceId,
            long authorization,
            long budgetId,
            DirectBuffer buffer,
            int offset,
            int limit,
            int maxLimit)
        {
            final int length = Math.max(Math.min(replyBudget - replyPadding, limit - offset), 0);

            if (length > 0)
            {
                final int reserved = length + replyPadding;

                replyBudget -= reserved;

                assert replyBudget >= 0;

                doData(network, routeId, replyId, traceId, authorization, budgetId,
                    reserved, buffer, offset, length, EMPTY_OCTETS);
            }

            final int maxLength = maxLimit - offset;
            final int remaining = maxLength - length;
            if (remaining > 0)
            {
                if (encodeSlot == NO_SLOT)
                {
                    encodeSlot = bufferPool.acquire(replyId);
                }
                else
                {
                    encodeSlotMaxLimit -= length;
                    assert encodeSlotMaxLimit >= 0;
                }

                if (encodeSlot == NO_SLOT)
                {
                    cleanupNetwork(traceId, authorization);
                }
                else
                {
                    final MutableDirectBuffer encodeBuffer = bufferPool.buffer(encodeSlot);
                    encodeBuffer.putBytes(0, buffer, offset + length, remaining);
                    encodeSlotOffset = remaining;
                }
            }
            else
            {
                cleanupEncodeSlotIfNecessary();
                if (sessions.isEmpty() && decoder == decodeIgnoreAll)
                {
                    doNetworkEnd(traceId, authorization);
                }
            }
        }

        private void onNetwork(
            int msgTypeId,
            DirectBuffer buffer,
            int index,
            int length)
        {
            switch (msgTypeId)
            {
            case BeginFW.TYPE_ID:
                final BeginFW begin = beginRO.wrap(buffer, index, index + length);
                onNetworkBegin(begin);
                break;
            case DataFW.TYPE_ID:
                final DataFW data = dataRO.wrap(buffer, index, index + length);
                onNetworkData(data);
                break;
            case EndFW.TYPE_ID:
                final EndFW end = endRO.wrap(buffer, index, index + length);
                onNetworkEnd(end);
                break;
            case AbortFW.TYPE_ID:
                final AbortFW abort = abortRO.wrap(buffer, index, index + length);
                onNetworkAbort(abort);
                break;
            case WindowFW.TYPE_ID:
                final WindowFW window = windowRO.wrap(buffer, index, index + length);
                onNetworkWindow(window);
                break;
            case ResetFW.TYPE_ID:
                final ResetFW reset = resetRO.wrap(buffer, index, index + length);
                onNetworkReset(reset);
                break;
            case SignalFW.TYPE_ID:
                final SignalFW signal = signalRO.wrap(buffer, index, index + length);
                onNetworkSignal(signal);
                break;
            default:
                break;
            }
        }

        private void onNetworkBegin(
            BeginFW begin)
        {
            final long traceId = begin.traceId();
            final long authorization = begin.authorization();

            state = AmqpState.openingInitial(state);

            doNetworkBegin(traceId, authorization);
            doNetworkWindow(traceId, authorization, bufferPool.slotCapacity(), 0, 0L);
        }

        private void onNetworkData(
            DataFW data)
        {
            final long traceId = data.traceId();
            final long authorization = data.authorization();

            initialBudget -= data.reserved();

            if (initialBudget < 0)
            {
                doNetworkReset(supplyTraceId.getAsLong(), authorization);
            }
            else
            {
                final long streamId = data.streamId();
                final long budgetId = data.budgetId();
                final OctetsFW payload = data.payload();

                DirectBuffer buffer = payload.buffer();
                int offset = payload.offset();
                int limit = payload.limit();

                if (decodeSlot != NO_SLOT)
                {
                    final MutableDirectBuffer slotBuffer = bufferPool.buffer(decodeSlot);
                    slotBuffer.putBytes(decodeSlotLimit, buffer, offset, limit - offset);
                    decodeSlotLimit += limit - offset;
                    buffer = slotBuffer;
                    offset = 0;
                    limit = decodeSlotLimit;
                }

                decodeNetwork(traceId, authorization, budgetId, buffer, offset, limit);
            }
        }

        private void releaseBufferSlotIfNecessary()
        {
            if (decodeSlot != NO_SLOT)
            {
                bufferPool.release(decodeSlot);
                decodeSlot = NO_SLOT;
                decodeSlotLimit = 0;
            }
        }

        private void onNetworkEnd(
            EndFW end)
        {
            final long authorization = end.authorization();

            state = AmqpState.closeInitial(state);

            if (decodeSlot == NO_SLOT)
            {
                final long traceId = end.traceId();

                cleanupStreams(traceId, authorization);

                doNetworkEndIfNecessary(traceId, authorization);
            }
            decoder = decodeIgnoreAll;
        }

        private void onNetworkAbort(
            AbortFW abort)
        {
            final long traceId = abort.traceId();
            final long authorization = abort.authorization();
            doNetworkAbort(traceId, authorization);
        }

        private void onNetworkWindow(
            WindowFW window)
        {
            final long traceId = window.traceId();
            final long authorization = window.authorization();
            final long budgetId = window.budgetId();
            final int credit = window.credit();
            final int padding = window.padding();

            state = AmqpState.openReply(state);

            replyBudget += credit;
            replyPadding += padding;

            if (encodeSlot != NO_SLOT)
            {
                final MutableDirectBuffer buffer = bufferPool.buffer(encodeSlot);
                final int limit = Math.min(encodeSlotOffset, encodeSlotMaxLimit);
                final int maxLimit = encodeSlotOffset;

                encodeNetwork(encodeSlotTraceId, authorization, budgetId, buffer, 0, limit, maxLimit);
            }

            final int slotCapacity = bufferPool.slotCapacity();
            sessions.values().forEach(s -> minimum.value = Math.min(s.remoteIncomingWindow, minimum.value));

            final int replySharedBudgetMax = sessions.values().size() > 0 ?
                Math.min(minimum.value * initialMaxFrameSize, replyBudget) : replyBudget;
            final int replySharedCredit = replySharedBudgetMax - Math.max(replySharedBudget, 0) - Math.max(encodeSlotOffset, 0);

            if (replySharedCredit > 0)
            {
                final long replySharedBudgetPrevious = creditor.credit(traceId, replyBudgetIndex, replySharedCredit);

                replySharedBudget += replySharedCredit;
                assert replySharedBudgetPrevious <= slotCapacity
                    : String.format("%d <= %d, replyBudget = %d",
                    replySharedBudgetPrevious, slotCapacity, replyBudget);

                assert credit <= slotCapacity
                    : String.format("%d <= %d", credit, slotCapacity);
            }
        }

        private void onNetworkReset(
            ResetFW reset)
        {
            final long traceId = reset.traceId();
            final long authorization = reset.authorization();

            cleanupBudgetCreditorIfNecessary();
            cleanupEncodeSlotIfNecessary();

            doNetworkReset(traceId, authorization);
        }

        private void onNetworkSignal(
            SignalFW signal)
        {
            final long traceId = signal.traceId();
            doNetworkSignal(traceId);
        }

        private void onDecodeError(
            long traceId,
            long authorization,
            AmqpErrorType errorType)
        {
            cleanupStreams(traceId, authorization);
            // TODO: if open-open has not been exchanged, cannot call doEncodeClose
            doEncodeClose(traceId, authorization, errorType);
            doNetworkEnd(traceId, authorization);
        }

        private void doNetworkBegin(
            long traceId,
            long authorization)
        {
            state = AmqpState.openingReply(state);

            doBegin(network, routeId, replyId, traceId, authorization, affinity, EMPTY_OCTETS);
            router.setThrottle(replyId, this::onNetwork);

            assert replyBudgetIndex == NO_CREDITOR_INDEX;
            this.replyBudgetIndex = creditor.acquire(replySharedBudgetId);
        }

        private void doNetworkData(
            long traceId,
            long authorization,
            long budgetId,
            Flyweight payload)
        {
            doNetworkData(traceId, authorization, budgetId, payload.buffer(), payload.offset(), payload.limit());
        }

        private void doNetworkData(
            long traceId,
            long authorization,
            long budgetId,
            DirectBuffer buffer,
            int offset,
            int limit)
        {
            int maxLimit = limit;

            if (encodeSlot != NO_SLOT)
            {
                final MutableDirectBuffer encodeBuffer = bufferPool.buffer(encodeSlot);
                encodeBuffer.putBytes(encodeSlotOffset, buffer, offset, limit - offset);
                encodeSlotOffset += limit - offset;
                encodeSlotTraceId = traceId;

                buffer = encodeBuffer;
                offset = 0;
                limit = Math.min(encodeSlotOffset, encodeSlotMaxLimit);
                maxLimit = encodeSlotOffset;
            }

            encodeNetwork(traceId, authorization, budgetId, buffer, offset, limit, maxLimit);
        }

        private void doNetworkEnd(
            long traceId,
            long authorization)
        {
            state = AmqpState.closeReply(state);

            cleanupBudgetCreditorIfNecessary();
            cleanupEncodeSlotIfNecessary();

            doEnd(network, routeId, replyId, traceId, authorization, EMPTY_OCTETS);
        }

        private void doNetworkAbort(
            long traceId,
            long authorization)
        {
            state = AmqpState.closeReply(state);

            cleanupBudgetCreditorIfNecessary();
            cleanupEncodeSlotIfNecessary();

            doAbort(network, routeId, replyId, traceId, authorization, EMPTY_OCTETS);
        }

        private void doNetworkReset(
            long traceId,
            long authorization)
        {
            state = AmqpState.closeInitial(state);

            cleanupDecodeSlotIfNecessary();

            doReset(network, routeId, initialId, traceId, authorization, EMPTY_OCTETS);
        }

        private void doNetworkWindow(
            long traceId,
            long authorization,
            int credit,
            int padding,
            long budgetId)
        {
            assert credit > 0;

            state = AmqpState.openInitial(state);

            initialBudget += credit;
            doWindow(network, routeId, initialId, traceId, authorization, budgetId, credit, padding, 0);
        }

        private void doNetworkSignal(
            long traceId)
        {
            doSignal(network, routeId, initialId, traceId);
        }

        private void decodeNetwork(
            long traceId,
            long authorization,
            long budgetId,
            DirectBuffer buffer,
            int offset,
            int limit)
        {
            AmqpServerDecoder previous = null;
            int progress = offset;
            while (progress <= limit && previous != decoder)
            {
                previous = decoder;
                progress = decoder.decode(this, traceId, authorization, budgetId, buffer, progress, limit);
            }

            if (progress < limit)
            {
                if (decodeSlot == NO_SLOT)
                {
                    decodeSlot = bufferPool.acquire(initialId);
                }

                if (decodeSlot == NO_SLOT)
                {
                    cleanupNetwork(traceId, authorization);
                }
                else
                {
                    final MutableDirectBuffer slotBuffer = bufferPool.buffer(decodeSlot);
                    decodeSlotLimit = limit - progress;
                    slotBuffer.putBytes(0, buffer, progress, decodeSlotLimit);
                }
            }
            else
            {
                cleanupDecodeSlotIfNecessary();

                if (AmqpState.initialClosed(state))
                {
                    cleanupStreams(traceId, authorization);
                    doNetworkEndIfNecessary(traceId, authorization);
                }
            }
        }

        private void onDecodeHeader(
            long traceId,
            long authorization,
            AmqpProtocolHeaderFW header)
        {
            if (isAmqpHeaderValid(header))
            {
                doEncodeProtocolHeader(header.major(), header.minor(), header.revision(), traceId, authorization);
            }
            else
            {
                onDecodeError(traceId, authorization, DECODE_ERROR);
            }
        }

        private void onDecodeFrameHeader(
            AmqpFrameHeaderFW frameHeader,
            Consumer<Builder> mutator)
        {
            amqpFrameHeaderRW.wrap(frameBuffer, 0, frameBuffer.capacity())
                    .size(0)
                    .doff(frameHeader.doff())
                    .type(frameHeader.type())
                    .channel(frameHeader.channel())
                    .performative(mutator);
        }

        private void onDecodeOpen(
            long traceId,
            long authorization,
            AmqpOpenFW open)
        {
            if (open.hasMaxFrameSize())
            {
                this.initialMaxFrameSize = (int) open.maxFrameSize();
            }
            doEncodeOpen(traceId, authorization, open.hasMaxFrameSize());
        }

        private void onDecodeBegin(
            long traceId,
            long authorization,
            AmqpBeginFW begin)
        {
            if (begin.hasRemoteChannel())
            {
                onDecodeError(traceId, authorization, NOT_ALLOWED);
            }
            else
            {
                this.channelId++;
                AmqpSession session = sessions.computeIfAbsent(channelId, AmqpSession::new);
                session.nextIncomingId((int) begin.nextOutgoingId());
                session.incomingWindow(writeBuffer.capacity());
                session.outgoingWindow(outgoingWindow);
                session.remoteIncomingWindow((int) begin.incomingWindow());
                session.remoteOutgoingWindow((int) begin.outgoingWindow());
                session.onDecodeBegin(traceId, authorization);
            }
        }

        private void onDecodeAttach(
            long traceId,
            long authorization,
            AmqpAttachFW attach,
            int channel)
        {
            AmqpSession session = sessions.get(channel);
            if (session != null)
            {
                session.onDecodeAttach(traceId, authorization, attach);
            }
            else
            {
                onDecodeError(traceId, authorization, NOT_ALLOWED);
            }
        }

        private void onDecodeFlow(
            long traceId,
            long authorization,
            AmqpFlowFW flow)
        {
            AmqpSession session = sessions.get(amqpFrameHeaderRO.channel());
            if (session != null)
            {
                session.onDecodeFlow(traceId, authorization, flow);
            }
            else
            {
                onDecodeError(traceId, authorization, NOT_ALLOWED);
            }
        }

        private void onDecodeClose(
            long traceId,
            long authorization,
            AmqpCloseFW close)
        {
            if (close.fieldCount() == 0)
            {
                sessions.values().forEach(s -> s.cleanup(traceId, authorization));
                doEncodeClose(traceId, authorization, null);
                doNetworkEndIfNecessary(traceId, authorization);
            }
        }

        private boolean isAmqpHeaderValid(
            AmqpProtocolHeaderFW header)
        {
            return PROTOCOL_HEADER == header.buffer().getLong(header.offset(), ByteOrder.BIG_ENDIAN);
        }

        private void cleanupNetwork(
            long traceId,
            long authorization)
        {
            cleanupStreams(traceId, authorization);

            doNetworkResetIfNecessary(traceId, authorization);
            doNetworkAbortIfNecessary(traceId, authorization);
        }

        private void cleanupStreams(
            long traceId,
            long authorization)
        {
            sessions.values().forEach(s -> s.cleanup(traceId, authorization));
        }

        private void doNetworkEndIfNecessary(
                long traceId,
                long authorization)
        {
            if (!AmqpState.replyClosed(state))
            {
                doNetworkEnd(traceId, authorization);
            }
        }

        private void doNetworkResetIfNecessary(
                long traceId,
                long authorization)
        {
            if (!AmqpState.initialClosed(state))
            {
                doNetworkReset(traceId, authorization);
            }
        }

        private void doNetworkAbortIfNecessary(
                long traceId,
                long authorization)
        {
            if (!AmqpState.replyClosed(state))
            {
                doNetworkAbort(traceId, authorization);
            }
        }

        private void cleanupBudgetCreditorIfNecessary()
        {
            if (replyBudgetIndex != NO_CREDITOR_INDEX)
            {
                creditor.release(replyBudgetIndex);
                replyBudgetIndex = NO_CREDITOR_INDEX;
            }
        }

        private void cleanupDecodeSlotIfNecessary()
        {
            if (decodeSlot != NO_SLOT)
            {
                bufferPool.release(decodeSlot);
                decodeSlot = NO_SLOT;
                decodeSlotLimit = 0;
            }
        }

        private void cleanupEncodeSlotIfNecessary()
        {
            if (encodeSlot != NO_SLOT)
            {
                bufferPool.release(encodeSlot);
                encodeSlot = NO_SLOT;
                encodeSlotOffset = 0;
                encodeSlotTraceId = 0;
            }
        }

        private final class AmqpSession
        {
            private final Long2ObjectHashMap<AmqpServerStream> links;
            private final int channelId;

            private int nextIncomingId;
            private int incomingWindow;
            private int nextOutgoingId;
            private int outgoingWindow;
            private int remoteIncomingWindow;
            private int remoteOutgoingWindow;

            private AmqpSession(
                int channelId)
            {
                this.links = new Long2ObjectHashMap<>();
                this.channelId = channelId;
                this.nextOutgoingId++;
            }

            private void nextIncomingId(
                int nextOutgoingId)
            {
                this.nextIncomingId = nextOutgoingId;
            }

            private void incomingWindow(
                int incomingWindow)
            {
                this.incomingWindow = incomingWindow;
            }

            private void outgoingWindow(
                int outgoingWindow)
            {
                this.outgoingWindow = outgoingWindow;
            }

            private void remoteIncomingWindow(
                int incomingWindow)
            {
                this.remoteIncomingWindow = incomingWindow;
            }

            private void remoteOutgoingWindow(
                int outgoingWindow)
            {
                this.remoteOutgoingWindow = outgoingWindow;
            }

            private void onDecodeBegin(
                long traceId,
                long authorization)
            {
                doEncodeBegin(traceId, authorization, channelId, nextOutgoingId);
            }

            private void onDecodeAttach(
                long traceId,
                long authorization,
                AmqpAttachFW attach)
            {
                final long linkKey = attach.handle();
                if (links.containsKey(linkKey))
                {
                    onDecodeError(traceId, authorization, NOT_ALLOWED);
                }
                else
                {
                    AmqpRole role = attach.role();
                    boolean hasSourceAddress = attach.hasSource() && attach.source().sourceList().hasAddress();
                    boolean hasTargetAddress = attach.hasTarget() && attach.target().targetList().hasAddress();

                    final RouteFW route;
                    switch (role)
                    {
                    case RECEIVER:
                        route = resolveTarget(routeId, authorization, hasSourceAddress ?
                                attach.source().sourceList().address() : null, AmqpCapabilities.RECEIVE_ONLY);
                        break;
                    case SENDER:
                        route = resolveTarget(routeId, authorization, hasTargetAddress ?
                                attach.target().targetList().address() : null, AmqpCapabilities.SEND_ONLY);
                        break;
                    default:
                        throw new IllegalStateException("Unexpected value: " + role);
                    }
                    if (route != null)
                    {
                        String address = null;
                        switch (role)
                        {
                        case RECEIVER:
                            if (hasSourceAddress)
                            {
                                address = attach.source().sourceList().address().asString();
                            }
                            break;
                        case SENDER:
                            if (hasTargetAddress)
                            {
                                address = attach.target().targetList().address().asString();
                            }
                            break;
                        }
                        AmqpServerStream link = new AmqpServerStream(address, role, route);
                        AmqpServerStream oldLink = links.put(linkKey, link);
                        assert oldLink == null;
                        link.onDecodeAttach(traceId, authorization, attach);
                    }
                    else
                    {
                        // TODO: reject
                    }
                }
            }

            private void onDecodeFlow(
                long traceId,
                long authorization,
                AmqpFlowFW flow)
            {
                int flowNextIncomingId = (int) flow.nextIncomingId();
                int flowIncomingWindow = (int) flow.incomingWindow();
                int flowNextOutgoingId = (int) flow.nextOutgoingId();
                int flowOutgoingWindow = (int) flow.outgoingWindow();
                assert flow.hasHandle() == flow.hasDeliveryCount();
                assert flow.hasHandle() == flow.hasLinkCredit();

                this.nextIncomingId = flowNextOutgoingId;
                this.remoteIncomingWindow = flowNextIncomingId + flowIncomingWindow - nextOutgoingId;
                this.remoteOutgoingWindow = flowOutgoingWindow;

                if (flow.hasHandle())
                {
                    AmqpServerStream attachedLink = links.get(flow.handle());
                    attachedLink.onDecodeFlow(traceId, authorization, flow.deliveryCount(), (int) flow.linkCredit());
                }
            }

            private void cleanup(
                long traceId,
                long authorization)
            {
                links.values().forEach(l -> l.cleanup(traceId, authorization));
            }

            private boolean hasSendCapability(
                    int capabilities)
            {
                return (capabilities & SEND_ONLY.value()) != 0;
            }

            private boolean hasReceiveCapability(
                    int capabilities)
            {
                return (capabilities & RECEIVE_ONLY.value()) != 0;
            }

            private class AmqpServerStream
            {
                private MessageConsumer application;
                private long newRouteId;
                private long initialId;
                private long replyId;
                private long budgetId;
                private int replyBudget;
                private long deliveryCount;
                private int linkCredit;

                private String name;
                private long handle;
                private AmqpRole role;
                private String address;

                private int state;
                private int capabilities;

                AmqpServerStream(
                    String address,
                    AmqpRole role,
                    RouteFW route)
                {
                    this.address = address;
                    this.role = role;
                    this.capabilities = 0;
                    this.newRouteId = route.correlationId();
                    this.initialId = supplyInitialId.applyAsLong(newRouteId);
                    this.replyId = supplyReplyId.applyAsLong(initialId);
                    this.application = router.supplyReceiver(initialId);
                }

                private void onDecodeAttach(
                    long traceId,
                    long authorization,
                    AmqpAttachFW attach)
                {
                    this.name = attach.name().asString();
                    this.handle = attach.handle();

                    final AmqpCapabilities capability = amqpCapabilities(role);
                    final AmqpSenderSettleMode amqpSenderSettleMode = attach.sndSettleMode();
                    final AmqpReceiverSettleMode amqpReceiverSettleMode = attach.rcvSettleMode();

                    doApplicationBeginIfNecessary(traceId, authorization, affinity, address, capability, amqpSenderSettleMode,
                        amqpReceiverSettleMode);
                    doApplicationData(traceId, authorization, role);

                    correlations.put(replyId, this::onApplication);
                }

                private void onDecodeFlow(
                    long traceId,
                    long authorization,
                    long deliveryCount,
                    int linkCredit)
                {
                    this.linkCredit = (int) (deliveryCount + linkCredit - this.deliveryCount);
                    this.deliveryCount = deliveryCount;
                    this.replyBudget = linkCredit * initialMaxFrameSize;
                    flushReplyWindow(traceId, authorization);
                }

                private void doApplicationBeginIfNecessary(
                    long traceId,
                    long authorization,
                    long affinity,
                    String targetAddress,
                    AmqpCapabilities capability,
                    AmqpSenderSettleMode senderSettleMode,
                    AmqpReceiverSettleMode receiverSettleMode)
                {
                    final int newCapabilities = capabilities | capability.value();
                    if (!AmqpState.initialOpening(state))
                    {
                        this.capabilities = newCapabilities;
                        doApplicationBegin(traceId, authorization, affinity, targetAddress, senderSettleMode,
                            receiverSettleMode);
                    }
                }

                private void doApplicationBegin(
                    long traceId,
                    long authorization,
                    long affinity,
                    String address,
                    AmqpSenderSettleMode senderSettleMode,
                    AmqpReceiverSettleMode receiverSettleMode)
                {
                    assert state == 0;
                    state = AmqpState.openingInitial(state);

                    router.setThrottle(initialId, this::onApplication);

                    final AmqpBeginExFW beginEx = amqpBeginExRW.wrap(extraBuffer, 0, extraBuffer.capacity())
                        .typeId(amqpTypeId)
                        .address(address)
                        .capabilities(r -> r.set(AmqpCapabilities.valueOf(capabilities)))
                        .senderSettleMode(s -> s.set(amqpSenderSettleMode(senderSettleMode)))
                        .receiverSettleMode(r -> r.set(amqpReceiverSettleMode(receiverSettleMode)))
                        .build();

                    doBegin(application, newRouteId, initialId, traceId, authorization, affinity, beginEx);
                }

                private void doApplicationData(
                    long traceId,
                    long authorization,
                    AmqpRole role)
                {
                    assert AmqpState.initialOpening(state);

                    switch (role)
                    {
                    case SENDER:
                        // TODO
                        break;
                    }
                }

                private void doApplicationAbort(
                    long traceId,
                    long authorization,
                    Flyweight extension)
                {
                    setInitialClosed();

                    doAbort(application, newRouteId, initialId, traceId, authorization, extension);
                }

                private void doApplicationAbortIfNecessary(
                    long traceId,
                    long authorization)
                {
                    if (!AmqpState.initialClosed(state))
                    {
                        doApplicationAbort(traceId, authorization, EMPTY_OCTETS);
                    }
                }

                private void setInitialClosed()
                {
                    assert !AmqpState.initialClosed(state);

                    state = AmqpState.closeInitial(state);

                    if (AmqpState.closed(state))
                    {
                        capabilities = 0;
                        links.remove(handle);
                    }
                }

                private void onApplication(
                    int msgTypeId,
                    DirectBuffer buffer,
                    int index,
                    int length)
                {
                    switch (msgTypeId)
                    {
                    case BeginFW.TYPE_ID:
                        final BeginFW begin = beginRO.wrap(buffer, index, index + length);
                        onApplicationBegin(begin);
                        break;
                    case DataFW.TYPE_ID:
                        final DataFW data = dataRO.wrap(buffer, index, index + length);
                        onApplicationData(data);
                        break;
                    case EndFW.TYPE_ID:
                        final EndFW end = endRO.wrap(buffer, index, index + length);
                        onApplicationEnd(end);
                        break;
                    case AbortFW.TYPE_ID:
                        final AbortFW abort = abortRO.wrap(buffer, index, index + length);
                        onApplicationAbort(abort);
                        break;
                    case WindowFW.TYPE_ID:
                        final WindowFW window = windowRO.wrap(buffer, index, index + length);
                        onApplicationWindow(window);
                        break;
                    case ResetFW.TYPE_ID:
                        final ResetFW reset = resetRO.wrap(buffer, index, index + length);
                        onApplicationReset(reset);
                        break;
                    case SignalFW.TYPE_ID:
                        final SignalFW signal = signalRO.wrap(buffer, index, index + length);
                        onApplicationSignal(signal);
                        break;
                    }
                }

                private void onApplicationWindow(
                    WindowFW window)
                {
                    final long traceId = window.traceId();
                    final long authorization = window.authorization();
                    final long budgetId = window.budgetId();
                    final int credit = window.credit();
                    final int padding = window.padding();

                    this.state = AmqpState.openInitial(state);
                    this.budgetId = budgetId;
                    initialBudget += credit;
                    initialPadding = padding;

                    if (AmqpState.initialClosing(state) && !AmqpState.initialClosed(state))
                    {
                        doApplicationEnd(traceId, authorization, EMPTY_OCTETS);
                    }
                }

                private void onApplicationReset(
                    ResetFW reset)
                {
                    setInitialClosed();

                    final long traceId = reset.traceId();
                    final long authorization = reset.authorization();
                    // TODO

                    cleanup(traceId, authorization);
                }

                private void onApplicationSignal(
                    SignalFW signal)
                {
                    final long signalId = signal.signalId();
                    // TODO
                }

                private boolean isReplyOpen()
                {
                    return AmqpState.replyOpened(state);
                }

                private void onApplicationBegin(
                    BeginFW begin)
                {
                    state = AmqpState.openReply(state);

                    final long traceId = begin.traceId();
                    final long authorization = begin.authorization();

                    final AmqpBeginExFW amqpBeginEx = begin.extension().get(amqpBeginExRO::tryWrap);
                    if (amqpBeginEx != null)
                    {
                        AmqpRole amqpRole = amqpRole(amqpBeginEx.capabilities().get());
                        AmqpSenderSettleMode amqpSenderSettleMode = amqpSenderSettleMode(amqpBeginEx.senderSettleMode().get());
                        AmqpReceiverSettleMode amqpReceiverSettleMode =
                            amqpReceiverSettleMode(amqpBeginEx.receiverSettleMode().get());
                        final String address = amqpBeginEx.address().asString();

                        deliveryCount = initialDeliveryCount;
                        doEncodeAttach(traceId, authorization, name, channelId, handle, amqpRole, amqpSenderSettleMode,
                            amqpReceiverSettleMode, address);
                    }
                }

                private void onApplicationData(
                    DataFW data)
                {
                    final long traceId = data.traceId();
                    final int reserved = data.reserved();
                    final long authorization = data.authorization();
                    final int flags = data.flags();
                    final OctetsFW extension = data.extension();

                    this.replyBudget -= reserved;
                    replySharedBudget -= reserved;

                    if (replyBudget < 0)
                    {
                        doApplicationReset(traceId, authorization);
                        doNetworkAbort(traceId, authorization);
                    }

                    remoteIncomingWindow--;
                    nextOutgoingId++;
                    outgoingWindow--;
                    deliveryCount++;
                    linkCredit--;
                    doEncodeTransfer(traceId, authorization, channelId, remoteIncomingWindow, handle, flags, extension,
                        data.payload());
                }

                private void onApplicationEnd(
                    EndFW end)
                {
                    setReplyClosed();
                }

                private void onApplicationAbort(
                    AbortFW abort)
                {
                    setReplyClosed();

                    final long traceId = abort.traceId();
                    final long authorization = abort.authorization();

                    cleanupCorrelationIfNecessary();
                    cleanup(traceId, authorization);
                }

                private void doApplicationEnd(
                    long traceId,
                    long authorization,
                    Flyweight extension)
                {
                    setInitialClosed();
                    capabilities = 0;
                    links.remove(handle);

                    doEnd(application, newRouteId, initialId, traceId, authorization, extension);
                }

                private void flushReplyWindow(
                    long traceId,
                    long authorization)
                {
                    if (isReplyOpen())
                    {
                        final int slotCapacity = bufferPool.slotCapacity();
                        final int maxNumberOfFrames = slotCapacity / initialMaxFrameSize +
                            ((slotCapacity % initialMaxFrameSize == 0) ? 0 : 1);
                        final int padding = 20 * maxNumberOfFrames + 205;
                        doWindow(application, newRouteId, replyId, traceId, authorization,
                            replySharedBudgetId, replyBudget, padding, initialMaxFrameSize);
                    }
                }

                private void doApplicationReset(
                    long traceId,
                    long authorization)
                {
                    setReplyClosed();

                    doReset(application, newRouteId, replyId, traceId, authorization, EMPTY_OCTETS);
                }

                private void doApplicationResetIfNecessary(
                    long traceId,
                    long authorization)
                {
                    correlations.remove(replyId);

                    if (!AmqpState.replyClosed(state))
                    {
                        doApplicationReset(traceId, authorization);
                    }
                }

                private void setReplyClosed()
                {
                    assert !AmqpState.replyClosed(state);

                    state = AmqpState.closeReply(state);

                    if (AmqpState.closed(state))
                    {
                        capabilities = 0;
                        links.remove(handle);
                    }
                }

                private void cleanup(
                    long traceId,
                    long authorization)
                {
                    doApplicationAbortIfNecessary(traceId, authorization);
                    doApplicationResetIfNecessary(traceId, authorization);
                }

                private boolean cleanupCorrelationIfNecessary()
                {
                    final MessageConsumer correlated = correlations.remove(replyId);
                    if (correlated != null)
                    {
                        router.clearThrottle(replyId);
                    }

                    return correlated != null;
                }
            }
        }
    }
}
