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
package org.reaktivity.nukleus.amqp.internal.util;

import org.reaktivity.nukleus.amqp.internal.types.codec.AmqpReceiverSettleMode;
import org.reaktivity.nukleus.amqp.internal.types.codec.AmqpRole;
import org.reaktivity.nukleus.amqp.internal.types.codec.AmqpSenderSettleMode;

public final class AmqpTypeUtil
{
    public static org.reaktivity.nukleus.amqp.internal.types.AmqpRole amqpRole(
        AmqpRole role)
    {
        switch (role)
        {
        case RECEIVER:
            return org.reaktivity.nukleus.amqp.internal.types.AmqpRole.RECEIVER;
        case SENDER:
            return org.reaktivity.nukleus.amqp.internal.types.AmqpRole.SENDER;
        default:
            throw new IllegalArgumentException("Illegal role: " + role);
        }
    }

    public static org.reaktivity.nukleus.amqp.internal.types.AmqpSenderSettleMode amqpSenderSettleMode(
        AmqpSenderSettleMode senderSettleMode)
    {
        switch (senderSettleMode)
        {
        case UNSETTLED:
            return org.reaktivity.nukleus.amqp.internal.types.AmqpSenderSettleMode.UNSETTLED;
        case SETTLED:
            return org.reaktivity.nukleus.amqp.internal.types.AmqpSenderSettleMode.SETTLED;
        case MIXED:
            return org.reaktivity.nukleus.amqp.internal.types.AmqpSenderSettleMode.MIXED;
        default:
            throw new IllegalArgumentException("Illegal senderSettleMode: " + senderSettleMode);
        }
    }

    public static org.reaktivity.nukleus.amqp.internal.types.AmqpReceiverSettleMode amqpReceiverSettleMode(
        AmqpReceiverSettleMode receiverSettleMode)
    {
        switch (receiverSettleMode)
        {
        case FIRST:
            return org.reaktivity.nukleus.amqp.internal.types.AmqpReceiverSettleMode.FIRST;
        case SECOND:
            return org.reaktivity.nukleus.amqp.internal.types.AmqpReceiverSettleMode.SECOND;
        default:
            throw new IllegalArgumentException("Illegal receiverSettleMode: " + receiverSettleMode);
        }
    }

    private AmqpTypeUtil()
    {
        // utility class, no instances
    }
}
