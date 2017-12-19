package net.kibotu.autonomous.logging.server;

import android.os.Bundle;
import android.support.v4.app.FragmentActivity;

import java.util.concurrent.TimeUnit;

import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

public class MainActivity extends FragmentActivity {

    private Disposable subscribe;

    int i = 0;
    private SimpleWebServer simpleWebServer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        subscribe = Observable.fromCallable(() -> "keks #" + (++i)).repeatWhen(o -> o.concatMap(v -> Observable.timer(1000, TimeUnit.MILLISECONDS)))
                .subscribeOn(Schedulers.newThread())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(msg -> {

                    SimpleWebServer.queue.add(new SimpleWebServer.ResponseMessage(msg));

                }, Throwable::printStackTrace);


        simpleWebServer = new SimpleWebServer(8080, getAssets());
        simpleWebServer.start();

    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        subscribe.dispose();
        simpleWebServer.stop();
    }
}