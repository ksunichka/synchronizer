package ru.ifmo.diploma.synchronizer.listeners;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import ru.ifmo.diploma.synchronizer.DirectoriesComparison;
import ru.ifmo.diploma.synchronizer.FileOperation;
import ru.ifmo.diploma.synchronizer.OperationType;
import ru.ifmo.diploma.synchronizer.messages.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.BlockingQueue;

/*
 * Created by Юлия on 06.06.2017.
 */
public class FileListener extends AbstractListener {
    private static final Logger LOG = LogManager.getLogger(FileListener.class);
    private BlockingQueue<FileOperation> fileOperations;

    public FileListener(String localAddr, BlockingQueue<AbstractMsg> tasks, DirectoriesComparison dc, BlockingQueue<FileOperation> fileOperations) {
        super(localAddr, tasks, dc);
        this.fileOperations = fileOperations;
    }

    @Override
    public void handle(AbstractMsg msg) {
        if (msg.getType() == MessageType.FILE) {
            LOG.debug("{}: Listener: FILE from {}", localAddr, msg.getSender());


            FileMsg fileMsg = (FileMsg) msg;
            byte[] fileContent = fileMsg.getFile();

            String p = dc.getAbsolutePath(fileMsg.getRelativePath());
            Path newDirPath = Paths.get(p.substring(0, p.lastIndexOf(File.separator)));

            boolean createDir = false;
            try {
                if (!Files.exists(newDirPath)) {
                    createDir = true;
                    Files.createDirectories(newDirPath);
                }
            } catch (IOException e) {
                tasks.offer(new ResultMsg(localAddr, msg.getSender(), MessageState.FAILED, msg));
                e.printStackTrace();
            }

            if (!Files.exists(Paths.get(dc.getAbsolutePath(fileMsg.getRelativePath()))) && !createDir) {
                fileOperations.add(new FileOperation(OperationType.ENTRY_COPY_OR_CREATE, dc.getAbsolutePath(fileMsg.getRelativePath())));
            } else {
                fileOperations.add(new FileOperation(OperationType.ENTRY_MODIFY, dc.getAbsolutePath(fileMsg.getRelativePath())));
            }

            try (OutputStream out = new FileOutputStream(dc.getAbsolutePath(fileMsg.getRelativePath()))) {

                out.write(fileContent);
                dc.setCreationTime(dc.getAbsolutePath(fileMsg.getRelativePath()), fileMsg.getCreationTime());
                if (!msg.isBroadcast()) {
                    tasks.offer(new ResultMsg(localAddr, msg.getSender(), MessageState.SUCCESS, msg));
                }
            } catch (IOException e) {
                if (!msg.isBroadcast()) {
                    tasks.offer(new ResultMsg(localAddr, msg.getSender(), MessageState.FAILED, msg));
                }
            }
        }
    }
}
