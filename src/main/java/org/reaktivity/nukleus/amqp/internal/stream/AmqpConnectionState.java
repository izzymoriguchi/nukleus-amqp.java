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

public enum AmqpConnectionState
{
    START
    {
        protected AmqpConnectionState receivedHeader()
        {
            return HDR_RCVD;
        }

        protected AmqpConnectionState sentHeader()
        {
            return HDR_SENT;
        }
    },
    HDR_RCVD
    {
        protected AmqpConnectionState sentHeader()
        {
            return HDR_EXCH;
        }
    },
    HDR_SENT
    {
        protected AmqpConnectionState receivedHeader()
        {
            return HDR_EXCH;
        }

        protected AmqpConnectionState sentOpen()
        {
            return OPEN_PIPE;
        }
    },
    HDR_EXCH
    {
        protected AmqpConnectionState receivedOpen()
        {
            return OPEN_RCVD;
        }

        protected AmqpConnectionState sentOpen()
        {
            return OPEN_SENT;
        }
    },
    OPEN_PIPE
    {
        protected AmqpConnectionState receivedHeader()
        {
            return OPEN_SENT;
        }

        protected AmqpConnectionState sentClose()
        {
            return OC_PIPE;
        }
    },
    OC_PIPE
    {
        protected AmqpConnectionState receivedHeader()
        {
            return CLOSE_PIPE;
        }
    },
    OPEN_RCVD
    {
        protected AmqpConnectionState sentOpen()
        {
            return OPENED;
        }
    },
    OPEN_SENT
    {
        protected AmqpConnectionState receivedOpen()
        {
            return OPENED;
        }

        protected AmqpConnectionState sentClose()
        {
            return CLOSE_PIPE;
        }
    },
    CLOSE_PIPE
    {
        protected AmqpConnectionState receivedOpen()
        {
            return CLOSE_SENT;
        }
    },
    OPENED
    {
        protected AmqpConnectionState receivedClose()
        {
            return CLOSE_RCVD;
        }

        protected AmqpConnectionState sentClose()
        {
            return CLOSE_SENT;
        }
    },
    CLOSE_RCVD
    {
        protected AmqpConnectionState sentClose()
        {
            return END;
        }
    },
    CLOSE_SENT
    {
        protected AmqpConnectionState receivedClose()
        {
            return END;
        }
    },
    DISCARDING,
    END,
    ERROR;

    protected AmqpConnectionState receivedHeader()
    {
        return ERROR;
    }

    protected AmqpConnectionState sentHeader()
    {
        return ERROR;
    }

    protected AmqpConnectionState receivedOpen()
    {
        return ERROR;
    }

    protected AmqpConnectionState sentOpen()
    {
        return ERROR;
    }

    protected AmqpConnectionState receivedClose()
    {
        return ERROR;
    }

    protected AmqpConnectionState sentClose()
    {
        return ERROR;
    }
}
