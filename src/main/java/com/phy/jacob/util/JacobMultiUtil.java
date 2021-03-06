package com.phy.jacob.util;

import com.alibaba.fastjson.JSON;
import com.jacob.activeX.ActiveXComponent;
import com.jacob.com.ComThread;
import com.jacob.com.Dispatch;
import com.jacob.com.Variant;
import org.springframework.util.FileCopyUtils;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;

/**
 * jacob工具类
 *
 * @author Ryan.Peng
 * @date 2019年5月29日
 */
public class JacobMultiUtil {

    /**
     * 各启动方式宏
     */
    public final static String MS_DOC = "Word.Application";
    public final static String MS_EXCEL = "Excel.Application";
    public final static String MS_PPT = "Powerpoint.Application";
    public final static String WPS_WPS = "KWPS.Application";
    public final static String WPS_ET = "KET.Application";
    public final static String WPS_DPS = "KWPP.Application";
    public static ConcurrentMap<ActiveXComponent, String> appMap = new ConcurrentHashMap<>();
    public static ConcurrentMap<ActiveXComponent, String> appPidMap = new ConcurrentHashMap<>();
    public static ConcurrentMap<ActiveXComponent, Long> keepAliveMap = new ConcurrentHashMap<>();
    public static Queue<ConvertedTarget> fileQueue = new ConcurrentLinkedQueue<>();
    public static Queue<ConvertedTarget> garbageFileQueue = new ConcurrentLinkedQueue<>();
    /**
     * 映射对应文档的文档对象类型
     **/
    private static Map<String, String> documentMap;
    /**
     * 转换成pdf文件对应的宏值的映射
     **/
    private static Map<String, Integer> pdfMacro;
    /**
     * office应用对应的进程值的映射
     **/
    private static Map<String, String> processNameMap;

    /**
     * 初始化映射关系
     */
    static {
        documentMap = new HashMap<>();
        pdfMacro = new HashMap<>();
        processNameMap = new HashMap<>();
        documentMap.put(MS_DOC, "Documents");
        documentMap.put(MS_EXCEL, "Workbooks");
        documentMap.put(MS_PPT, "Presentations");
        documentMap.put(WPS_WPS, "Documents");
        documentMap.put(WPS_ET, "Workbooks");
        documentMap.put(WPS_DPS, "Presentations");
        pdfMacro.put(MS_DOC, 17);
        pdfMacro.put(MS_PPT, 32);
        pdfMacro.put(WPS_WPS, 17);
        pdfMacro.put(WPS_DPS, 32);
        processNameMap.put(WPS_WPS, "WPS.EXE");
        processNameMap.put(MS_DOC, "WINWORD.EXE");
    }

    public synchronized static void init(String type) {
        try {
            if (appMap.isEmpty()) {
                createAppThread(type);
                keepAliveThread(type);
                garbageThread();
            }
        } catch (Exception e) {
            System.err.println("初始化Jacob进程错误");
            e.printStackTrace();
        }
    }

    public static Set<String> getWinwordPid(String processName) {
        Set<String> result = new HashSet<>();
        try {
            String cmd = "tasklist /V /FO CSV /FI \"IMAGENAME eq " + processName + "\"";
//            System.out.println(cmd);
            BufferedReader br = new BufferedReader(new InputStreamReader(Runtime.getRuntime().exec(cmd).getInputStream(), Charset.forName("GBK")));
            String line;
            while ((line = br.readLine()) != null) {
                if (line != null && line.split(",")[0].contains(processName)) {
                    result.add(StringUtils.replace(line.split(",")[1], "\"", ""));
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return result;
    }

    public static void createAppThread(String type) throws Exception {
        System.out.println("正在初始化转换程序...");
        System.out.println("结束操作系统Offince进程");
        String cmd = "taskkill /F /IM " + processNameMap.get(type);
        Runtime.getRuntime().exec(cmd);
        Thread.sleep(200);
        int processorNum = Runtime.getRuntime().availableProcessors();
        System.out.println("启用多线程支持");
        ComThread.InitMTA();
        for (int i = 0; i < processorNum; i++) {
            new Thread(new JacobMultiUtil.JacobThread(fileQueue, appMap, type), "Converter - " + (i + 1)).start();
            System.out.println("创建线程:" + "Converter - " + (i + 1));
        }
    }

    public static void garbageThread() {
        System.out.println("创建临时文件清理线程");
        new Thread(() -> {
            while (true) {
                if (!garbageFileQueue.isEmpty()) {
                    int i = 1;
                    while (garbageFileQueue.iterator().hasNext()) {
                        ConvertedTarget ct = garbageFileQueue.poll();
                        ct.getInputFile().delete();
                        ct.getOutputFile().delete();
                        i++;
                    }
                    System.out.println("本次清理了临时文件个数:" + 2 * i);
                }
                try {
                    Thread.sleep(6 * 60 * 60 * 1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }, "garbageThread").start();
    }


    public static void keepAliveThread(String type) {
        System.out.println("创建keepAlive线程");
        new Thread(() -> {
            while (true) {
                try {
//                    System.out.println("\n线程心跳检测");
                    Long now = System.currentTimeMillis();
                    for (ActiveXComponent app : keepAliveMap.keySet()) {
                        Long alive = keepAliveMap.get(app);
                        Long stay = now - alive;
//                        System.out.println(app + " 线程活跃值:" + stay);
                        if (stay > 2 * 60 * 1000) {
                            String pid = appPidMap.get(app);

                            System.out.println("发现僵死线程,pid:" + pid + ",活跃值:" + (now - alive) + "ms");
                            String cmd = "taskkill /F /PID " + pid;
                            Runtime.getRuntime().exec(cmd);
                            System.out.println("结束winword.exe进程完毕");
                            killThreadByName(appMap.get(app));
                            System.out.println("结束僵死线程完毕");
                            new Thread(new JacobThread(fileQueue, appMap, type), "Converter - " + app.hashCode()).start();
                            //队列中移除app
                            appMap.remove(app);
                            appPidMap.remove(app);
                            keepAliveMap.remove(app);
                            app = null;
                            System.out.println("创建新线程替换僵死线程");

                        }
                    }
                    Thread.sleep(30 * 1000);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }, "keepAliveThread").start();
    }

    public static void quit() {
        try {
            for (ActiveXComponent app : appMap.keySet()) {
                if (app != null) {
                    app.invoke("Quit", new Variant[]{});
                    app = null;
                }
            }
            ComThread.Release();
            for (String processName : processNameMap.values()) {
                String cmd = "taskkill /F /IM " + processName;
                Runtime.getRuntime().exec(cmd);
            }
        } catch (IOException e) {
            System.err.println("清理Jacob进程错误");
            e.printStackTrace();
        }
    }

    public static boolean killThreadByName(String name) {
        Thread[] threads = findAllThread();
        for (Thread thread : threads) {
            if (thread.getName().equalsIgnoreCase(name)) {
                thread.interrupt();
                System.out.println("调用interrupt中断线程:" + name);
                return true;
            }
        }
        return false;
    }

    public static Thread[] findAllThread() {
        ThreadGroup currentGroup = Thread.currentThread().getThreadGroup();
        while (currentGroup.getParent() != null) {
            // 返回此线程组的父线程组
            currentGroup = currentGroup.getParent();
        }
        //此线程组中活动线程的估计数
        int noThreads = currentGroup.activeCount();
        Thread[] lstThreads = new Thread[noThreads];
        //把对此线程组中的所有活动子组的引用复制到指定数组中。
        currentGroup.enumerate(lstThreads);

//        for (Thread thread : lstThreads) {
//            System.out.println("线程数量：" + noThreads + " 线程id：" + thread.getId() + " 线程名称：" + thread.getName() + " 线程状态：" + thread.getState());
//        }
        return lstThreads;
    }

    public static void main(String[] args) throws Exception {
//        JacobMultiUtil.init(WPS_WPS);
//        File templateDir = new File("C:\\Users\\pengh\\Desktop\\2e39a731-7586-4263-b626-51b6e8bbabd4.doc");
//        File templateDir2 = new File("C:\\Users\\pengh\\Desktop\\2e39a731-7586-4263-b626-51b6e8bbabd4.pdf");
////        List<File> files = listFiles(templateDir).stream().filter(file -> file.getName().endsWith(".doc")).collect(Collectors.toList());
//        List<ConvertedTarget> convertedTargets = new ArrayList<>();
//        ConvertedTarget ct = new ConvertedTarget(templateDir, templateDir2);
//        convertedTargets.add(ct);
//        JacobMultiUtil.fileQueue.addAll(convertedTargets);


        for (String s : getWinwordPid("Maxthon.exe")) {
            System.out.println(s);
        }
    }

    /**
     * 遍历目录文件
     *
     * @param directory
     * @return
     */
    public static List<File> listFiles(File directory) {

        ArrayList list = new ArrayList(1000);
        File[] files = directory.listFiles();
        for (File file : files) {
            if (file.isDirectory()) {
                list.addAll(listFiles(file));
            } else {
                list.add(file);
            }
        }
        return list;
    }

    /**
     * 按指定大小，分隔集合，将集合按规定个数分为n个部分
     *
     * @param <T>
     * @param list
     * @param len
     * @return
     */
    public static <T> List<List<T>> splitList(List<T> list, int len) {

        if (list == null || list.isEmpty() || len < 1) {
            return Collections.emptyList();
        }

        List<List<T>> result = new ArrayList<>();

        int size = list.size();
        int count = (size + len - 1) / len;

        for (int i = 0; i < count; i++) {
            List<T> subList = list.subList(i * len, ((i + 1) * len > size ? size : len * (i + 1)));
            result.add(subList);
        }

        return result;
    }


    public static synchronized ActiveXComponent createApp(String type) {
        ActiveXComponent app = new ActiveXComponent(type);
//        try {
//            Thread.sleep(200);
//        } catch (InterruptedException e) {
//            e.printStackTrace();
//        }
        //记录对应的winword进程
        Set<String> pids = getWinwordPid(processNameMap.get(type));
//        System.out.println("type="+type+"pids="+pids);
        for (String pid : pids) {
            if (!appPidMap.containsValue(pid)) {
                appPidMap.put(app, pid);
                System.out.println("记录window进程映射,key=" + app + ",value=" + pid);
            }
        }
        return app;
    }

    public static class ConvertedTarget {
        //标识该文件已被处理,并通知线程
        private CountDownLatch countDownLatch = new CountDownLatch(1);
        private File inputFile;
        private File outputFile;
        private String base64File;
        private byte[] byteFile;
        private int tryCount;

        public ConvertedTarget(File inputFile) {
            this.inputFile = inputFile;
        }

        public ConvertedTarget(File inputFile, File outputFile) {
            this.inputFile = inputFile;
            this.outputFile = outputFile;
        }

        public ConvertedTarget(String base64File) {
            this.base64File = base64File;
        }

        public ConvertedTarget(byte[] byteFile) {
            this.byteFile = byteFile;
        }

        public int getTryCount() {
            return tryCount;
        }

        public void setTryCount(int tryCount) {
            this.tryCount = tryCount;
        }

        public CountDownLatch getCountDownLatch() {
            return countDownLatch;
        }

        public void setCountDownLatch(CountDownLatch countDownLatch) {
            this.countDownLatch = countDownLatch;
        }

        public File getInputFile() {
            return inputFile;
        }

        public void setInputFile(File inputFile) {
            this.inputFile = inputFile;
        }

        public File getOutputFile() {
            return outputFile;
        }

        public void setOutputFile(File outputFile) {
            this.outputFile = outputFile;
        }

        public String getBase64File() {
            return base64File;
        }

        public void setBase64File(String base64File) {
            this.base64File = base64File;
        }

        public byte[] getByteFile() {
            return byteFile;
        }

        public void setByteFile(byte[] byteFile) {
            this.byteFile = byteFile;
        }
    }

    /**
     * Jacob线程类,加速处理
     */
    public static class JacobThread implements Runnable {
        private ConcurrentMap<ActiveXComponent, String> appQueueMap;
        private Queue<ConvertedTarget> files;
        private String type;

        JacobThread(Queue<ConvertedTarget> files, ConcurrentMap<ActiveXComponent, String> appQueueMap, String type) {
            this.files = files;
            this.appQueueMap = appQueueMap;
            this.type = type;
        }

        @Override
        public void run() {
            Dispatch documents;
            String filePath = null;
            ActiveXComponent app = createApp(type);
            appQueueMap.put(app, Thread.currentThread().getName());
            app.setProperty("Visible", new Variant(false));
            documents = app.getProperty(documentMap.get(type)).toDispatch();
            while (true) {
                ConvertedTarget f = files.poll();
                //更新进程活跃状态
                keepAliveMap.put(app, System.currentTimeMillis());
                try {
                    if (f != null) {
                        if (f.getInputFile().isFile()) {
                            long st = System.currentTimeMillis();
                            filePath = f.getInputFile().getAbsolutePath();
                            Dispatch document = Dispatch.call(documents, "Open", filePath).toDispatch();
                            String outFilePath = filePath.substring(0, filePath.lastIndexOf(".")) + ".pdf";
                            Dispatch.call(document, "SaveAs", outFilePath, new Variant(pdfMacro.get(type)));
                            Dispatch.call(document, "Close", false);
                            System.out.println(Thread.currentThread().getName() + " PDF转换成功,文件位置:\n" + outFilePath);
                            File outputFile = new File(outFilePath);
                            if (outputFile.isFile()) {
                                f.setOutputFile(outputFile);
                                f.countDownLatch.countDown();
                                //正常转换转入待清理队列
                                garbageFileQueue.add(f);
                            } else {
                                if (f.getTryCount() < 3) {
                                    f.setTryCount(f.getTryCount() + 1);
                                    System.err.println("\n" + Thread.currentThread().getName() + " 转换文件异常,重新进入队列,文件位置:\n" + filePath);
                                    files.offer(f);
                                } else {
                                    System.err.println("\n" + Thread.currentThread().getName() + " 3次尝试转换文件异常,已排除队列,文件位置:\n" + filePath);
                                    FileCopyUtils.copy(f.getInputFile(), new File(System.getenv("TEMP") + File.separatorChar + f.getInputFile().getName()));
                                }
                            }
                            long et = System.currentTimeMillis();
                            System.out.println(Thread.currentThread().getName() + " 转换耗时: " + (et - st) + "ms");
                        } else {
                            System.err.println(Thread.currentThread().getName() + " 获取到的inputFile不存在: " + JSON.toJSONString(f));
                        }
                    } else {
//                        System.out.println(Thread.currentThread().getName() + " 队列中没有需要转换的文件");
                        Thread.sleep(50);
                    }
                } catch (InterruptedException ie) {
                    System.err.println(Thread.currentThread().getName() + " 收到中断信号");
                    break;
                } catch (Exception e) {
                    System.err.println("\n" + Thread.currentThread().getName() + " 转换异常,重新进入队列,文件位置:\n" + filePath);
                    e.printStackTrace();
                    if (f != null && f.getTryCount() < 3) {
                        f.setTryCount(f.getTryCount() + 1);
                        files.offer(f);
                    }
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e1) {
                        break;
                    }
                }
            }
        }
    }
}
