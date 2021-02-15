package org.archive.modules.browser;

import org.archive.util.ArchiveUtils;
import org.archive.util.Recorder;

import java.io.File;

/**
 * Worker thread for processing subrequests.
 */
public class SubrequestThread extends Thread {
    private final int serialNumber;
    private final SubrequestScheduler scheduler;

    SubrequestThread(SubrequestScheduler scheduler, int serialNumber) {
        super(SubrequestThread.class.getName() + " #" + serialNumber);
        this.scheduler = scheduler;
        this.serialNumber = serialNumber;
        setDaemon(true);
    }

    @Override
    public void run() {
        Recorder recorder = new Recorder(new File("/tmp"), "hsr" + serialNumber);
        try {
            Recorder.setHttpRecorder(recorder);
            while (true) {
                ArchiveUtils.continueCheck();
                Subrequest subrequest = scheduler.next();
                if (subrequest == null) return;
                try {
                    subrequest.execute();
                } finally {
                    scheduler.finished(subrequest);
                }
            }
        } catch (InterruptedException e) {
            // just exit
        } finally {
            recorder.cleanup();
        }
    }
}
