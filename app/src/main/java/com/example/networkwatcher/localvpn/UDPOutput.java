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
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

public class UDPOutput implements Runnable
{
    private static final String TAG = UDPOutput.class.getSimpleName();

    private android.net.VpnService vpnService;
    private ConcurrentLinkedQueue<Packet> inputQueue;
    private Selector selector;
    private ConcurrentLinkedQueue<Runnable> selectorQueue;

    private static final int MAX_CACHE_SIZE = 2000;
    private LRUCache<String, DatagramChannel> channelCache =
            new LRUCache<>(MAX_CACHE_SIZE, new LRUCache.CleanupCallback<String, DatagramChannel>()
            {
                @Override
                public void cleanup(Map.Entry<String, DatagramChannel> eldest)
                {
                    closeChannel(eldest.getValue());
                }
            });

    public UDPOutput(ConcurrentLinkedQueue<Packet> inputQueue, Selector selector, ConcurrentLinkedQueue<Runnable> selectorQueue, android.net.VpnService vpnService)
    {
        this.inputQueue = inputQueue;
        this.selector = selector;
        this.selectorQueue = selectorQueue;
        this.vpnService = vpnService;
    }

    @Override
    public void run()
    {
        Log.i(TAG, "Started");
        try
        {

            Thread currentThread = Thread.currentThread();
            while (true)
            {
                Packet currentPacket;
                // TODO: Block when not connected
                do
                {
                    currentPacket = inputQueue.poll();
                    if (currentPacket != null)
                        break;
                    Thread.sleep(1); // Gecikme azaltıldı
                } while (!currentThread.isInterrupted());

                if (currentThread.isInterrupted())
                    break;

                InetAddress destinationAddress = currentPacket.ip4Header.destinationAddress;
                int destinationPort = currentPacket.udpHeader.destinationPort;
                int sourcePort = currentPacket.udpHeader.sourcePort;

                String ipAndPort = destinationAddress.getHostAddress() + ":" + destinationPort + ":" + sourcePort;
                DatagramChannel outputChannel = channelCache.get(ipAndPort);
                if (outputChannel == null) {
                    outputChannel = DatagramChannel.open();
                    // outputChannel.socket().setSendBufferSize(5 * 1024 * 1024); // İPTAL: Sabit 5MB RAM ayırmak çoklu bağlantılarda OOM (Out Of Memory) ve tıkanmaya sebep olur.
                    // outputChannel.socket().setReceiveBufferSize(5 * 1024 * 1024); // İPTAL
                    vpnService.protect(outputChannel.socket());
                    try
                    {
                        outputChannel.connect(new InetSocketAddress(destinationAddress, destinationPort));
                    }
                    catch (IOException e)
                    {
                        Log.e(TAG, "Connection error: " + ipAndPort, e);
                        closeChannel(outputChannel);
                        ByteBufferPool.release(currentPacket.backingBuffer);
                        continue;
                    }
                    outputChannel.configureBlocking(false);
                    currentPacket.swapSourceAndDestination();

                    final DatagramChannel finalChannel = outputChannel;
                    final Packet finalPacket = currentPacket;
                    selectorQueue.offer(() -> {
                        try
                        {
                            if (finalChannel.isOpen())
                            {
                                finalChannel.register(selector, SelectionKey.OP_READ, finalPacket);
                            }
                        }
                        catch (IOException e)
                        {
                            Log.e(TAG, "Failed to register UDP channel to selector", e);
                        }
                    });
                    selector.wakeup();

                    channelCache.put(ipAndPort, outputChannel);
                }

                try
                {
                    ByteBuffer payloadBuffer = currentPacket.backingBuffer;
                    while (payloadBuffer.hasRemaining()) {
                        int written = outputChannel.write(payloadBuffer);
                        if (written == 0) {
                            Log.w(TAG, "UDP socket write buffer (5MB) full! Dropping packet.");
                            break; // UDP paketini düşür (gerçek dünyada da UDP paketleri düşebilir)
                        }
                    }
                }
                catch (IOException e)
                {
                    Log.e(TAG, "Network write error: " + ipAndPort, e);
                    channelCache.remove(ipAndPort);
                    closeChannel(outputChannel);
                }
                ByteBufferPool.release(currentPacket.backingBuffer);
                //hping3 --fast -S -p 80 8.8.8.8
                //ping -i 0.2 8.8.8.8
            }
        }
        catch (InterruptedException e)
        {
            Log.i(TAG, "Stopping");
        }
        catch (Throwable e)
        {
            Log.e(TAG, "UDPOutput Fatal Error: " + e.toString(), e);
        }
        finally
        {
            closeAll();
        }
    }

    private void closeAll()
    {
        Iterator<Map.Entry<String, DatagramChannel>> it = channelCache.entrySet().iterator();
        while (it.hasNext())
        {
            closeChannel(it.next().getValue());
            it.remove();
        }
    }

    private void closeChannel(DatagramChannel channel)
    {
        try
        {
            channel.close();
        }
        catch (IOException e)
        {
            // Ignore
        }
    }
}
