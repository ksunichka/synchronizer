package ru.ifmo.diploma.synchronizer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import ru.ifmo.diploma.synchronizer.messages.*;

import java.io.*;
import java.math.BigInteger;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.BlockingQueue;

/*
 * Created by Юлия on 19.05.2017.
 */
public class DirectoriesComparison {
    private List<FileInfo> filesInfo = new ArrayList<>();
    private List<FileInfo> prevDirState;
    private final String startPath;

    private final String localAddr;
    private BlockingQueue<AbstractMsg> tasks;
    private String logRelativePath;

    private static final Logger LOG = LogManager.getLogger(DirectoriesComparison.class);


    public DirectoriesComparison(String startPath, String localAddr, BlockingQueue<AbstractMsg> tasks, String logRelativePath) {

        this.startPath = startPath;
        this.localAddr = localAddr;
        this.tasks = tasks;
        this.logRelativePath = logRelativePath;
    }


    //записывает информацию обо всех файлах в список
    private void createListFiles(String path) throws IOException, NoSuchAlgorithmException {
        File dir = new File(path);
        File[] listFiles = dir.listFiles();

        if (listFiles != null) {
            List<File> files = Arrays.asList(listFiles);

            for (File f : files) {
                if (f.isDirectory())
                    createListFiles(f.getAbsolutePath());
                else if (!f.getName().equals(logRelativePath)) {
                    BasicFileAttributes attrs = Files.readAttributes(f.toPath(), BasicFileAttributes.class);
                    String relativePath = f.getPath().substring(startPath.length() + 1);
                    filesInfo.add(new FileInfo(relativePath, attrs.creationTime().toMillis(), f.lastModified(), f.length(), getCheckSum(f.toPath())));
                }
            }
            LOG.debug("List of files descriptors on {} was created", localAddr);
        } else {
            LOG.debug("Synchronized directory on {} is empty", localAddr);
        }
    }

    public List<FileInfo> getListFiles() throws IOException, NoSuchAlgorithmException {
        if (filesInfo.isEmpty())
            createListFiles(startPath);

        return filesInfo;
    }

    public String getStartPath() {
        return startPath;
    }

    public List<FileInfo> getPrevDirState() {
        return prevDirState;
    }

    public void setPrevDirState(List<FileInfo> state) {
        prevDirState = state;
    }

    public String getAbsolutePath(String relativePath) {
        return startPath + "\\" + relativePath;
    }

    public void setCreationTime(String fileName, long creationTime) {
        FileTime newCreationTime = FileTime.fromMillis(creationTime);
        Path path = Paths.get(fileName);
        try {
            Files.setAttribute(path, "basic:creationTime", newCreationTime, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    private String getCheckSum(Path path) throws NoSuchAlgorithmException, IOException {
        MessageDigest md = MessageDigest.getInstance("MD5");

        try (InputStream is = Files.newInputStream(path);
             DigestInputStream dis = new DigestInputStream(is, md)) {
            int l;
            byte[] buf = new byte[1024];
            while ((l = dis.read(buf)) > 0) {
                md.update(buf, 0, l);
            }

        }
        byte[] digest = md.digest();

        return (new BigInteger(1, digest)).toString(32);
    }


    private boolean isFirstLaunch(List<FileInfo> l1, List<FileInfo> l2) {
        if (!Files.exists(Paths.get(getAbsolutePath(logRelativePath)))) {
            LOG.debug("First launch on " + localAddr);

            for (FileInfo fi1 : l1) {
                for (FileInfo fi2 : l2) {
                    if (fi1.getRelativePath().equals(fi2.getRelativePath()) && fi1.getCheckSum().equals(fi2.getCheckSum()))
                        if (fi1.getCreationDate() > fi2.getCreationDate())
                            setCreationTime(getAbsolutePath(fi1.getRelativePath()), fi2.getCreationDate());
                }
            }

            prevDirState = new ArrayList<>();
            return true;
        }
        return false;
    }

    private void checkForLocalChanges(String addr) {

        prevDirState = getDirectoryState(getAbsolutePath(logRelativePath));

        for (FileInfo fi1 : prevDirState) {
            boolean isOriginal = false, isCopied = false;
            boolean isMatch = false;

            for (FileInfo fi2 : filesInfo) {
                if (fi1.equals(fi2)) {
                    isOriginal = true;
                    isMatch = true;
                    continue;
                }

                if (fi1.getCheckSum().equals(fi2.getCheckSum())) {
                    if (fi1.getCreationDate() == fi2.getCreationDate()) {
                        isMatch = true;

                        int finishIndex1 = fi1.getRelativePath().lastIndexOf("\\") > 0 ? fi1.getRelativePath().lastIndexOf("\\") : 0;
                        int finishIndex2 = fi2.getRelativePath().lastIndexOf("\\") > 0 ? fi2.getRelativePath().lastIndexOf("\\") : 0;
                        if (fi1.getRelativePath().substring(0, finishIndex1).equals(fi2.getRelativePath().substring(0, finishIndex2))) {

                            LOG.debug("{}: File {} was locally renamed to {}", localAddr, fi1.getRelativePath(), fi2.getRelativePath());

                            AbstractMsg msg = new RenameFileMsg(localAddr, addr, fi1.getRelativePath(), fi2.getRelativePath());
                            tasks.offer(msg);


                        } else {

                            LOG.debug("{}: File {} was locally moved to {}", localAddr, fi1.getRelativePath(), fi2.getRelativePath());

                            AbstractMsg msg = new TransferFileMsg(localAddr, addr, fi1.getRelativePath(), fi2.getRelativePath());
                            tasks.offer(msg);

                        }

                    } else {

                        isCopied = true;
                        isMatch = true;
                        LOG.debug("{}: File {} was locally copied to {}", localAddr, fi1.getRelativePath(), fi2.getRelativePath());

                        AbstractMsg msg = new CopyFileMsg(localAddr, addr, fi1.getRelativePath(), fi2.getRelativePath(), fi2.getCreationDate());
                        tasks.offer(msg);

                    }
                }
            }
            if (!isOriginal && isCopied || !isMatch) {
                LOG.debug("{}: File {} was locally deleted", localAddr, fi1.getRelativePath());

                AbstractMsg msg = new DeleteFileMsg(localAddr, addr, fi1.getRelativePath());
                tasks.offer(msg);
            }
        }

    }

    public void compareDirectories(String addr, List<FileInfo> localListFiles, List<FileInfo> sentListFiles) {
        LOG.debug("{}: Comparing directories with {} started", localAddr, addr);

        boolean isFound;

        if (!isFirstLaunch(localListFiles, sentListFiles))
            checkForLocalChanges(addr);

        for (FileInfo sentFileInfo : sentListFiles) {
            isFound = false;

            for (FileInfo localFileInfo : localListFiles) {

                if (sentFileInfo.getCheckSum().equals(localFileInfo.getCheckSum())) {
                    LOG.debug("{}: Files: {}, {} have same content", localAddr, localFileInfo.getRelativePath(), sentFileInfo.getRelativePath());
                    isFound = true;
                    break;
                }
                if (sentFileInfo.getCreationDate() == localFileInfo.getCreationDate()) {
                    if (sentFileInfo.getUpdateDate() > localFileInfo.getUpdateDate()) {
                        LOG.debug("{}: File {} was modified, request it", localAddr, sentFileInfo.getRelativePath());
                        AbstractMsg msg = new SendFileRequestMsg(localAddr, addr, sentFileInfo, false);
                        tasks.offer(msg);

                        isFound = true;
                        break;
                    }
                }
                if (sentFileInfo.getRelativePath().equals(localFileInfo.getRelativePath())) {
                    LOG.debug("{}: Files have same relative paths ({}), but different content, " +
                            "Request file and save it with different name", localAddr, sentFileInfo.getRelativePath());
                    AbstractMsg msg = new SendFileRequestMsg(localAddr, addr, sentFileInfo, true);
                    tasks.offer(msg);

                }

            }
            //Не было совпадения для файла=> ищем его в списке с прошлой версией
            if (!isFound) {

                for (FileInfo prevFI : prevDirState) {
                    if (sentFileInfo.getCheckSum().equals(prevFI.getCheckSum())) {
                        isFound = true;
                        LOG.debug("{}: File {} was locally deleted", localAddr, sentFileInfo.getRelativePath());

                        AbstractMsg msg = new DeleteFileMsg(localAddr, addr, sentFileInfo.getRelativePath());
                        tasks.offer(msg);

                        break;
                    }
                }
                if (!isFound) {

                    LOG.debug("{}: File {} was created, request it", localAddr, sentFileInfo.getRelativePath());

                    AbstractMsg msg = new SendFileRequestMsg(localAddr, addr, sentFileInfo, false);
                    tasks.offer(msg);

                }
            }
        }
        // Exchange finished
        LOG.debug("{}: ListFiles comparison  with {} complete", localAddr, addr);
    }


    public void saveDirectoryState(String path) {
        //Запись списка файлов в log
        LOG.debug("{}: start saving directory state to {}", localAddr, path);

        try (OutputStream out = new FileOutputStream(path);
             ObjectOutputStream objFileOut = new ObjectOutputStream(out)) {
            createListFiles(startPath);
            for (FileInfo fi : filesInfo) {
                objFileOut.writeObject(fi);
                objFileOut.flush();
            }

            LOG.debug("{}: directory state saved to {}", localAddr, path);

        } catch (IOException | NoSuchAlgorithmException e) {
            LOG.error("{}: Can't save directory state", localAddr);
            e.printStackTrace();
        }
    }

    private List<FileInfo> getDirectoryState(String path) {
        List<FileInfo> result = new ArrayList<>();

        try (InputStream fin = new FileInputStream(path);
             ObjectInputStream oin = new ObjectInputStream(fin)) {


            while (fin.available() > 0) {
                FileInfo fi = (FileInfo) oin.readObject();
                result.add(fi);
            }


        } catch (IOException | ClassNotFoundException e) {
            e.printStackTrace();
        }
        return result;
    }

}
