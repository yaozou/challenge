package com.aliyun.filter;


import com.aliyun.common.Packet;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;

import static com.aliyun.common.Const.*;


/**
 * 数据处理线程
 * 从网络中获取数据，并按照页存放
 * 为每一页建立索引，并且造出错误
 * 根据traceId查询出错误的日志
 */
public class Data {
    public static final int PER_READ_LEN = 32 * 1024 * 1024;//每次读取长度
    private static int totalPageCount = 100000;//表示总页数，当真正的页数被计算出来过后会赋值给他
    private static long startTime;
    //用于存放错误的日志

    private static byte[] tail = new byte[1024];//尾巴数据
    private static int tailLen = 0;//尾巴数据的长度
    private static int pageIndex;//表示多少页

    public static void start() {
        try {
            start0();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    /**
     * 负责读取数据
     */
    public static void start0() throws Exception {
        startTime = System.currentTimeMillis();

        String path = "http://127.0.0.1:" + data_port + (listen_port == 8000 ? "/trace1.data" : "/trace2.data");
        System.out.println(path);
        URL url = new URL(path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
        InputStream in = conn.getInputStream();

        int n, len;
        byte[] data;
        Page page;
        do {
            //获取一个buf
            page = Container.getEmptyPage(pageIndex);
            page.clear();
            page.pageIndex = pageIndex;

            //将尾巴复制到缓冲区中
            data = page.data;
            System.arraycopy(tail, 0, data, 0, tailLen);
            len = tailLen;

            //读取一页数据，
            while ((n = in.read(data, len, Page.LEN - len)) != -1) {
                len += n;
                if (len == Page.LEN) break;
            }

            //反向找到换行符
            for (tailLen = 0; tailLen < 1000; tailLen++) {
                if (data[len - 1 - tailLen] == '\n') {//
                    System.arraycopy(data, len - tailLen, tail, 0, tailLen);
                    break;
                }
            }

            Container.moveEmptyToFull(pageIndex);

            //计算长度
            page.len = len - tailLen;
            asyncHandleData(page);
            pageIndex++;
        } while (n != -1);
        totalPageCount = pageIndex;

        System.out.println("read data total time=" + System.currentTimeMillis() + " - " + startTime + "=" + (System.currentTimeMillis() - startTime));

    }

    /**
     * 创建索引->找出错误->发送错误
     */
    public static void asyncHandleData(Page page) {
        new Thread(() -> {
            page.createIndexAndFindError();
            Container.moveFullToHandle(page.pageIndex);
            //发送错误出去
            filter.sendPacket(page.errPkt);
        }).start();//异步处理数据
    }

}
