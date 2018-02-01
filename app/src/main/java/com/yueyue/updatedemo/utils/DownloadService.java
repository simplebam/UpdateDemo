package com.yueyue.updatedemo.utils;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.FileProvider;
import android.text.TextUtils;

import com.yueyue.updatedemo.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Created by snail
 * on 2017/12/7.
 * Todo 下载的服务
 */

public class DownloadService extends Service {

    //定义notify的id，避免与其它的notification的处理冲突
    private static final int NOTIFY_ID = 0;
    private static final String CHANNEL = "update";

    private DownloadBinder binder = new DownloadBinder();
    private NotificationManager mNotificationManager;
    private NotificationCompat.Builder mBuilder;
    private DownloadCallback callback;

    //定义个更新速率，避免更新通知栏过于频繁导致卡顿
    private float rate = .0f;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void unbindService(ServiceConnection conn) {
        super.unbindService(conn);
        mNotificationManager.cancelAll();
        mNotificationManager = null;
        mBuilder = null;
    }

    /**
     * 和activity通讯的binder
     */
    public class DownloadBinder extends Binder {
        public DownloadService getService() {
            return DownloadService.this;
        }
    }

    /**
     * 创建通知栏
     */
    private void setNotification() {
        //全面解析Notification - CSDN博客
        //         http://blog.csdn.net/u012124438/article/details/53574649
        if (mNotificationManager == null) {
            mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        }
        mBuilder = new NotificationCompat.Builder(this, CHANNEL);
        mBuilder.setContentTitle("开始下载")
                .setContentText("正在连接服务器")
                .setSmallIcon(R.mipmap.ic_launcher_round)
                .setLargeIcon(BitmapFactory.decodeResource(getResources(), R.mipmap.ic_launcher))
                .setOngoing(true)  //ture，设置他为一个正在进行的通知。他们通常是用来表示一个后台任务,用户积极参与(如播放音乐)或以某种方式正在等待,因此占用设备(如一个文件下载,同步操作,主动网络连接)
                .setAutoCancel(true)////设置这个标志当用户单击面板就可以让通知将自动取消
                .setWhen(System.currentTimeMillis());
        mNotificationManager.notify(NOTIFY_ID, mBuilder.build());
    }

    /**
     * 下载完成
     */
    private void complete(String msg) {
        if (mBuilder != null) {
            mBuilder.setContentTitle("新版本").setContentText(msg);
            Notification notification = mBuilder.build();
            notification.flags = Notification.FLAG_AUTO_CANCEL;//用户单击通知后自动消失
            mNotificationManager.notify(NOTIFY_ID, notification);
        }
        stopSelf();//停止服务
    }

    /**
     * 开始下载apk
     */
    public void downApk(String url, DownloadCallback callback) {
        this.callback = callback;
        if (TextUtils.isEmpty(url)) {
            complete("下载路径错误");
            return;
        }
        setNotification();
        handler.sendEmptyMessage(0);
        Request request = new Request.Builder().url(url).build();
        new OkHttpClient().newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Message message = Message.obtain();
                message.what = 1;
                message.obj = e.getMessage();
                handler.sendMessage(message);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                //OkHttp踩坑记：为何 response.body().string() 只能调用一次？
                //       http://mp.weixin.qq.com/s/5dYV_urnRzucR84K2Z08tA
                if (response.body() == null) {
                    Message message = Message.obtain();
                    message.what = 1;
                    message.obj = "下载错误";
                    handler.sendMessage(message);
                    return;
                }
                InputStream is = null;
                byte[] buff = new byte[2048];
                int len;
                FileOutputStream fos = null;
                try {
                    is = response.body().byteStream();
                    long total = response.body().contentLength();
                    File file = createFile();
                    fos = new FileOutputStream(file);
                    long sum = 0;
                    while ((len = is.read(buff)) != -1) {
                        fos.write(buff, 0, len);
                        sum += len;
                        int progress = (int) (sum * 1.0f / total * 100);
                        if (rate != progress) {
                            Message message = Message.obtain();
                            message.what = 2;
                            message.obj = progress;
                            handler.sendMessage(message);
                            rate = progress;
                        }
                    }
                    fos.flush();
                    Message message = Message.obtain();
                    message.what = 3;
                    message.obj = file.getAbsoluteFile();
                    handler.sendMessage(message);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    try {
                        if (is != null)
                            is.close();
                        if (fos != null)
                            fos.close();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        });
    }


    /**
     * 路径为根目录
     * 创建文件名称为 updateDemo.apk
     */
    private File createFile() {
        String root = Environment.getExternalStorageDirectory().getPath();
        File file = new File(root, "updateDemo.apk");
        if (file.exists())
            file.delete();
        try {
            file.createNewFile();
            return file;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * 把处理结果放回ui线程
     */
    private Handler handler = new Handler(new Handler.Callback() {
        @Override
        public boolean handleMessage(Message msg) {
            switch (msg.what) {
                case 0:
                    callback.onPrepare();
                    break;

                case 1:
                    mNotificationManager.cancel(NOTIFY_ID);
                    callback.onFail((String) msg.obj);
                    stopSelf();//停止服务
                    break;

                case 2: {
                    //Android官方开发文档Training系列课程中文版：通知用户之在通知中显示进度-布布扣-bubuko.com
                    //     http://www.bubuko.com/infodetail-1630910.html
                    int progress = (int) msg.obj;
                    callback.onProgress(progress);
                    mBuilder.setContentTitle("正在下载：新版本...")
                            .setContentText(String.format(Locale.CHINESE, "%d%%", progress))
                            .setProgress(100, progress, false)
                            .setWhen(System.currentTimeMillis());
                    Notification notification = mBuilder.build();
                    notification.flags = Notification.FLAG_AUTO_CANCEL;//用户单击通知后自动消失
                    mNotificationManager.notify(NOTIFY_ID, notification);
                }
                break;

                case 3: {
                    callback.onComplete((File) msg.obj);
                    //app运行在界面,直接安装
                    //否则运行在后台则通知形式告知完成
                    if (onFront()) {
                        mNotificationManager.cancel(NOTIFY_ID);
                    } else {
                        Intent intent = installIntent((String) msg.obj);
                        PendingIntent pIntent = PendingIntent.getActivity(getApplicationContext(),
                                0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
                        ////setDefaults表示它会根据当前手机的环境决定播放什么铃声,以及如何振动
                        mBuilder.setContentIntent(pIntent)
                                .setContentTitle(getPackageName())
                                .setContentText("下载完成，点击安装")
                                .setProgress(0, 0, false)
                                .setDefaults(Notification.DEFAULT_ALL);
                        Notification notification = mBuilder.build();
                        notification.flags = Notification.FLAG_AUTO_CANCEL;
                        mNotificationManager.notify(NOTIFY_ID, notification);
                    }
                    stopSelf();
                }
                break;
            }
            return false;
        }
    });


    /**
     * 是否运行在用户前面
     */
    private boolean onFront() {
        ActivityManager activityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> appProcesses = activityManager.getRunningAppProcesses();
        if (appProcesses == null || appProcesses.isEmpty()) return false;

        for (ActivityManager.RunningAppProcessInfo appProcess : appProcesses) {
            if (appProcess.processName.equals(getPackageName()) &&
                    appProcess.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                return true;
            }
        }
        return false;
    }


    /**
     * 安装
     * 7.0 以上记得配置 fileProvider
     */
    private Intent installIntent(String path) {
        try {
            File file = new File(path);
            String authority = getApplicationContext().getPackageName() + ".fileProvider";
            Uri fileUri = FileProvider.getUriForFile(getApplicationContext(), authority, file);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                intent.setDataAndType(fileUri, "application/vnd.android.package-archive");
            } else {
                intent.setDataAndType(Uri.fromFile(file), "application/vnd.android.package-archive");
            }
            return intent;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }


    /**
     * 销毁时清空一下对notify对象的持有
     */
    @Override
    public void onDestroy() {
        mNotificationManager = null;
        super.onDestroy();
    }


    /**
     * 定义一下回调方法
     */
    public interface DownloadCallback {
        void onPrepare();

        void onProgress(int progress);

        void onComplete(File file);

        void onFail(String msg);
    }
}
