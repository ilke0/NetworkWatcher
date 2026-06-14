/*
** Copyright 2015, Mohamed Naufal
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

package com.example.networkwatcher.localvpn;

import android.util.Log;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.example.networkwatcher.localvpn.Packet.TCPHeader;
import com.example.networkwatcher.localvpn.TCB.TCBStatus;

public class TCPOutput implements Runnable
{
    private static final String TAG = TCPOutput.class.getSimpleName();

    private android.net.VpnService vpnService;
    private ConcurrentLinkedQueue<Packet> inputQueue;
    private ConcurrentLinkedQueue<ByteBuffer> outputQueue;
    private Selector selector;
    private ConcurrentLinkedQueue<Runnable> selectorQueue;

    private Random random = new Random();
    public TCPOutput(ConcurrentLinkedQueue<Packet> inputQueue, ConcurrentLinkedQueue<ByteBuffer> outputQueue,
                     Selector selector, ConcurrentLinkedQueue<Runnable> selectorQueue, android.net.VpnService vpnService)
    {
        this.inputQueue = inputQueue;
        this.outputQueue = outputQueue;
        this.selector = selector;
        this.selectorQueue = selectorQueue;
        this.vpnService = vpnService;
    }

    @Override
    public void run()
    {
        Log.i(TAG, "Started");
        Thread currentThread = Thread.currentThread();
        while (true)
        {
            try
            {
                Packet currentPacket;
                // TODO: Block when not connected
                do
                {
                    currentPacket = inputQueue.poll();
                    if (currentPacket != null)
                        break;
                    // Spin döngüsü ile bekle (CPU yormadan)
                    Thread.yield();
                } while (!currentThread.isInterrupted());

                if (currentThread.isInterrupted())
                    break;

                ByteBuffer payloadBuffer = currentPacket.backingBuffer;
                currentPacket.backingBuffer = null;
                ByteBuffer responseBuffer = ByteBufferPool.acquire();

                InetAddress destinationAddress = currentPacket.ip4Header.destinationAddress;

                TCPHeader tcpHeader = currentPacket.tcpHeader;
                int destinationPort = tcpHeader.destinationPort;
                int sourcePort = tcpHeader.sourcePort;

                String ipAndPort = destinationAddress.getHostAddress() + ":" +
                        destinationPort + ":" + sourcePort;
                TCB tcb = TCB.getTCB(ipAndPort);
                if (tcb == null)
                    initializeConnection(ipAndPort, destinationAddress, destinationPort,
                            currentPacket, tcpHeader, responseBuffer);
                else if (tcpHeader.isSYN())
                    processDuplicateSYN(tcb, tcpHeader, responseBuffer);
                else if (tcpHeader.isRST())
                    closeCleanly(tcb, responseBuffer);
                else if (tcpHeader.isFIN())
                    processFIN(tcb, tcpHeader, responseBuffer);
                else if (tcpHeader.isACK())
                    processACK(tcb, tcpHeader, payloadBuffer, responseBuffer);

                // XXX: cleanup later
                if (responseBuffer.position() == 0)
                    ByteBufferPool.release(responseBuffer);
                ByteBufferPool.release(payloadBuffer);
            }
            catch (Throwable e)
            {
                // Artık InterruptedException dışındaki hatalar thread'i öldürmeyecek
                if (e instanceof InterruptedException || currentThread.isInterrupted()) {
                    Log.i(TAG, "Stopping");
                    break;
                }
                Log.e(TAG, "TCPOutput error: " + e.toString(), e);
            }
        }
        TCB.closeAll();
    }

    private void initializeConnection(String ipAndPort, InetAddress destinationAddress, int destinationPort,
                                      Packet currentPacket, TCPHeader tcpHeader, ByteBuffer responseBuffer)
            throws IOException
    {
        currentPacket.swapSourceAndDestination();
        if (tcpHeader.isSYN())
        {
            SocketChannel outputChannel = SocketChannel.open();
            outputChannel.configureBlocking(false);
            // outputChannel.socket().setSendBufferSize(5 * 1024 * 1024); // İPTAL: Her TCP bağlantısı için 5MB buffer ayırmak, bir web sitesine girildiğinde açılan 100+ bağlantıda RAM'i tüketir ve ağı tıkar. OS dinamiğine bırakıldı.
            // outputChannel.socket().setReceiveBufferSize(5 * 1024 * 1024); // İPTAL
            vpnService.protect(outputChannel.socket());

            TCB tcb = new TCB(ipAndPort, random.nextInt(Short.MAX_VALUE + 1), tcpHeader.sequenceNumber, tcpHeader.sequenceNumber + 1,
                    tcpHeader.acknowledgementNumber, outputChannel, currentPacket);
            TCB.putTCB(ipAndPort, tcb);

            try
            {
                outputChannel.connect(new InetSocketAddress(destinationAddress, destinationPort));
                if (outputChannel.finishConnect())
                {
                    tcb.status = TCBStatus.SYN_RECEIVED;
                    // TODO: Set MSS for receiving larger packets from the device
                    currentPacket.updateTCPBuffer(responseBuffer, (byte) (TCPHeader.SYN | TCPHeader.ACK),
                            tcb.mySequenceNum, tcb.myAcknowledgementNum, 0);
                    tcb.mySequenceNum++; // SYN counts as a byte
                }
                else
                {
                    tcb.status = TCBStatus.SYN_SENT;
                    final SocketChannel finalChannel = outputChannel;
                    final TCB finalTcb = tcb;
                    selectorQueue.offer(() -> {
                        try
                        {
                            if (finalChannel.isOpen())
                            {
                                finalTcb.selectionKey = finalChannel.register(selector, SelectionKey.OP_CONNECT, finalTcb);
                            }
                        }
                        catch (IOException e)
                        {
                            Log.e(TAG, "Failed to register TCP OP_CONNECT to selector", e);
                        }
                    });
                    selector.wakeup();
                    return;
                }
            }
            catch (IOException e)
            {
                Log.e(TAG, "Connection error: " + ipAndPort, e);
                currentPacket.updateTCPBuffer(responseBuffer, (byte) TCPHeader.RST, 0, tcb.myAcknowledgementNum, 0);
                TCB.closeTCB(tcb);
            }
        }
        else
        {
            currentPacket.updateTCPBuffer(responseBuffer, (byte) TCPHeader.RST,
                    0, tcpHeader.sequenceNumber + 1, 0);
        }
        outputQueue.offer(responseBuffer);
    }

    private void processDuplicateSYN(TCB tcb, TCPHeader tcpHeader, ByteBuffer responseBuffer)
    {
        synchronized (tcb)
        {
            if (tcb.status == TCBStatus.SYN_SENT || tcb.status == TCBStatus.SYN_RECEIVED)
            {
                tcb.myAcknowledgementNum = tcpHeader.sequenceNumber + 1;
                return;
            }
        }
        // Eğer zaten kurulmuşsa (ESTABLISHED), mükerrer SYN'i görmezden gel, RST gönderme!
        if (tcb.status == TCBStatus.ESTABLISHED) {
            ByteBufferPool.release(responseBuffer);
            return;
        }
        sendRST(tcb, 1, responseBuffer);
    }

    private void processFIN(TCB tcb, TCPHeader tcpHeader, ByteBuffer responseBuffer)
    {
        synchronized (tcb)
        {
            Packet referencePacket = tcb.referencePacket;
            tcb.myAcknowledgementNum = tcpHeader.sequenceNumber + 1;
            tcb.theirAcknowledgementNum = tcpHeader.acknowledgementNumber;

            if (tcb.waitingForNetworkData)
            {
                tcb.status = TCBStatus.CLOSE_WAIT;
                referencePacket.updateTCPBuffer(responseBuffer, (byte) TCPHeader.ACK,
                        tcb.mySequenceNum, tcb.myAcknowledgementNum, 0);
            }
            else
            {
                tcb.status = TCBStatus.LAST_ACK;
                referencePacket.updateTCPBuffer(responseBuffer, (byte) (TCPHeader.FIN | TCPHeader.ACK),
                        tcb.mySequenceNum, tcb.myAcknowledgementNum, 0);
                tcb.mySequenceNum++; // FIN counts as a byte
            }
        }
        outputQueue.offer(responseBuffer);
    }

    private void processACK(TCB tcb, TCPHeader tcpHeader, ByteBuffer payloadBuffer, ByteBuffer responseBuffer) throws IOException
    {
        int payloadSize = payloadBuffer.limit() - payloadBuffer.position();
        SocketChannel outputChannel;
        synchronized (tcb)
        {
            outputChannel = tcb.channel;
            if (tcb.status == TCBStatus.SYN_RECEIVED)
            {
                tcb.status = TCBStatus.ESTABLISHED;

                final SocketChannel finalChannel = outputChannel;
                final TCB finalTcb = tcb;
                selectorQueue.offer(() -> {
                    try
                    {
                        if (finalChannel.isOpen())
                        {
                            finalTcb.selectionKey = finalChannel.register(selector, SelectionKey.OP_READ, finalTcb);
                        }
                    }
                    catch (IOException e)
                    {
                        Log.e(TAG, "Failed to register TCP OP_READ to selector", e);
                    }
                });
                selector.wakeup();
                tcb.waitingForNetworkData = true;
            }
            else if (tcb.status == TCBStatus.LAST_ACK)
            {
                closeCleanly(tcb, responseBuffer);
                return;
            }
        }

        if (payloadSize > 0)
        {
            boolean waitingForNetworkData;
            synchronized (tcb) { waitingForNetworkData = tcb.waitingForNetworkData; }

            if (!waitingForNetworkData)
            {
                final TCB finalTcb = tcb;
                selectorQueue.offer(() -> {
                    try
                    {
                        synchronized (finalTcb)
                        {
                            if (finalTcb.selectionKey != null && finalTcb.selectionKey.isValid())
                            {
                                finalTcb.selectionKey.interestOps(SelectionKey.OP_READ);
                            }
                        }
                    }
                    catch (Exception e)
                    {
                        Log.e(TAG, "Failed to update TCP interestOps to OP_READ", e);
                    }
                });
                selector.wakeup();
                synchronized (tcb) {
                    tcb.waitingForNetworkData = true;
                }
            }

            // Forward to remote server
            int startPosition = payloadBuffer.position();
            try
            {
                while (payloadBuffer.hasRemaining()) {
                    int written = outputChannel.write(payloadBuffer);
                    if (written == 0) {
                        Log.w(TAG, "Socket write buffer (5MB) completely full! Dropping packet for TCP Retransmission.");
                        break; 
                    }
                }
            }
            catch (IOException e)
            {
                Log.e(TAG, "Network write error: " + tcb.ipAndPort, e);
                sendRST(tcb, payloadSize, responseBuffer);
                return;
            }

            int bytesWritten = payloadBuffer.position() - startPosition;
            if (bytesWritten == 0 && payloadSize > 0) {
                return; 
            }

            synchronized (tcb) {
                tcb.myAcknowledgementNum = tcpHeader.sequenceNumber + bytesWritten;
                tcb.theirAcknowledgementNum = tcpHeader.acknowledgementNumber;
                Packet referencePacket = tcb.referencePacket;
                referencePacket.updateTCPBuffer(responseBuffer, (byte) TCPHeader.ACK, tcb.mySequenceNum, tcb.myAcknowledgementNum, 0);
            }
            outputQueue.offer(responseBuffer);
        }
        else {
            ByteBufferPool.release(responseBuffer);
        }
    }

    private void sendRST(TCB tcb, int prevPayloadSize, ByteBuffer buffer)
    {
        tcb.referencePacket.updateTCPBuffer(buffer, (byte) TCPHeader.RST, 0, tcb.myAcknowledgementNum + prevPayloadSize, 0);
        outputQueue.offer(buffer);
        TCB.closeTCB(tcb);
    }

    private void closeCleanly(TCB tcb, ByteBuffer buffer)
    {
        ByteBufferPool.release(buffer);
        TCB.closeTCB(tcb);
    }
}
