package ru.ifmo.diploma.synchronizer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import ru.ifmo.diploma.synchronizer.messages.AbstractMsg;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static java.nio.file.StandardWatchEventKinds.*;


/*
 * Created by Юлия on 18.05.2017.
 */
public class DirectoryChangesViewer implements Runnable {
    private static final Logger LOG = LogManager.getLogger(DirectoryChangesViewer.class);

    private final WatchService watcher;
    private final Map<WatchKey, Path> keys;
    private String localAddr;
    private final String startPath;
    private BlockingQueue<FileOperation> fileOperations;
    private BlockingQueue<AbstractMsg> tasks;


    DirectoryChangesViewer(Path dir, String localAddr, BlockingQueue<FileOperation> fileOperations,BlockingQueue<AbstractMsg> tasks) throws IOException {
        this.watcher = FileSystems.getDefault().newWatchService();
        this.keys = new HashMap<>();
        this.localAddr = localAddr;
        startPath=dir.toString();

        this.fileOperations=fileOperations;
        this.tasks=tasks;
        walkAndRegisterDirectories(dir);
    }


    private void walkAndRegisterDirectories(final Path start) throws IOException {

        Files.walkFileTree(start, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                WatchKey key = dir.register(watcher, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
                keys.put(key, dir);

                return FileVisitResult.CONTINUE;
            }
        });
    }


    @Override
    public void run() {
        BlockingQueue<Event> events=new LinkedBlockingQueue<>();

        Thread processor=new Thread(new EventsProcessor(localAddr,events,startPath, fileOperations, tasks));
        processor.start();

        LOG.debug("Directory changes viewer started on {}", localAddr);
        while (!Thread.currentThread().isInterrupted()) {

            WatchKey key;
            try {
                key = watcher.take();
            } catch (InterruptedException x) {
                LOG.debug("Directory changes viewer interrupted on {}", localAddr);
                return;
            }

            Path dir = keys.get(key);

            for (WatchEvent event : key.pollEvents()) {

                Path watchedObj = dir.resolve(((WatchEvent<Path>) event).context());
                if (!watchedObj.endsWith("log.bin")) {
                    try {
                        if (!Files.isDirectory(watchedObj)) {
                            events.offer(new Event(System.currentTimeMillis(), event, watchedObj));
                        } else {
                            if ("ENTRY_CREATE".equals(event.kind().name()))
                                walkAndRegisterDirectories(watchedObj);
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }

                }
            }

            if (!key.reset()) {
                keys.remove(key);

                if (keys.isEmpty()) {
                    break;
                }
            }
        }
    }

}
