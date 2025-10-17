package com.cobuilding;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.DirectoryChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipInputStream;

public class LauncherController {

    // --- НАСТРОЙКИ GITHUB ---
    private static final String GITHUB_USERNAME = "rakit1";
    private static final String GITHUB_REPO = "cobuildingsborka";
    private static final String CLIENT_VERSION_URL = "https://raw.githubusercontent.com/" + GITHUB_USERNAME + "/" + GITHUB_REPO + "/main/latest_version.txt";
    private static final String CLIENT_DOWNLOAD_URL_TEMPLATE = "https://github.com/" + GITHUB_USERNAME + "/" + GITHUB_REPO + "/releases/download/%s/client.zip";
    private static final String CLIENT_CHECKSUM_URL = "https://raw.githubusercontent.com/" + GITHUB_USERNAME + "/" + GITHUB_REPO + "/main/checksums.txt";

    // --- ОСТАЛЬНЫЕ НАСТРОЙКИ ---
    private static final String GAME_DIRECTORY_NAME = ".aurora-client";
    private static final String JAVA_DIRECTORY_NAME = "runtime";
    private static final String JAVA_RUNTIME_URL = "https://github.com/rakit1/cobuildingsborka/releases/download/Java/Java.zip";
    private static final String CLIENT_ARCHIVE_NAME = "client-backup.zip";

    private static final Set<String> PRESERVED_FILES = Set.of(
            "saves", "resourcepacks", "shaderpacks", "screenshots", "mods",
            "options.txt", "servers.dat", "logs", "runtime",
            "nickname.txt", "current_version.txt", "manifest.txt", "ram.txt",
            "client-backup.zip", "backup_version.txt"
    );

    private static final List<String> CORE_DIRECTORIES = List.of("libraries", "versions", "assets");

    @FXML private Label statusLabel;
    @FXML private TextField nicknameField;
    @FXML private ProgressBar progressBar;
    @FXML private Button launchButton;
    @FXML private BorderPane rootPane;
    @FXML private HBox titleBar;
    @FXML private Button minimizeButton;
    @FXML private Button closeButton;
    @FXML private Slider ramSlider;
    @FXML private VBox mainView;
    @FXML private VBox settingsView;
    @FXML private Button settingsButton;
    @FXML private Button backButton;
    @FXML private TextField gamePathField;
    @FXML private Button changePathButton;

    private double xOffset = 0;
    private double yOffset = 0;

    private Path gameDirectoryPath;
    private Path javaDirectoryPath;
    private final Path configFilePath = Paths.get(System.getProperty("user.home"), ".aurora-launcher-config.txt");

    private static final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS).build();

    @FXML
    public void initialize() {
        gameDirectoryPath = loadGameDirectoryPath();
        javaDirectoryPath = gameDirectoryPath.resolve(JAVA_DIRECTORY_NAME);

        titleBar.setOnMousePressed(event -> {
            xOffset = event.getSceneX();
            yOffset = event.getSceneY();
        });
        titleBar.setOnMouseDragged(event -> {
            Stage stage = (Stage) rootPane.getScene().getWindow();
            stage.setX(event.getScreenX() - xOffset);
            stage.setY(event.getScreenY() - yOffset);
        });

        closeButton.setOnAction(e -> Platform.exit());
        minimizeButton.setOnAction(e -> ((Stage) rootPane.getScene().getWindow()).setIconified(true));

        settingsButton.setOnAction(e -> toggleSettingsView(true));
        backButton.setOnAction(e -> toggleSettingsView(false));

        nicknameField.setText(loadNickname());
        ramSlider.setValue(loadRamSetting());
        gamePathField.setText(gameDirectoryPath.getParent().toAbsolutePath().toString());

        launchButton.setOnAction(e -> {
            if (nicknameField.getText().trim().isEmpty()) {
                updateStatus("Ошибка: введите никнейм!");
                return;
            }
            saveNickname(nicknameField.getText().trim());
            saveRamSetting((int) ramSlider.getValue());

            launchButton.setDisable(true);
            nicknameField.setDisable(true);
            settingsButton.setDisable(true);

            new Thread(this::checkUpdateAndLaunch).start();
        });

        changePathButton.setOnAction(e -> handleChangeGamePath());
    }

    private void handleChangeGamePath() {
        DirectoryChooser directoryChooser = new DirectoryChooser();
        directoryChooser.setTitle("Выберите новую папку для клиента");
        File selectedDirectory = directoryChooser.showDialog(rootPane.getScene().getWindow());

        if (selectedDirectory != null) {
            Path newBasePath = selectedDirectory.toPath();
            String title = "Переместить клиент?";
            String content = "Ваши сохранения, моды и ресурспаки будут ПЕРЕМЕЩЕНЫ.\n\n" +
                    "Старый путь:\n" + gameDirectoryPath.getParent() +
                    "\n\nНовый путь:\n" + newBasePath;

            boolean confirmed = showCustomConfirmDialog(title, content);

            if (confirmed) {
                launchButton.setDisable(true);
                settingsButton.setDisable(true);
                new Thread(() -> {
                    try {
                        Path newGamePath = newBasePath.resolve(GAME_DIRECTORY_NAME);
                        Platform.runLater(() -> updateStatus("Перемещение данных..."));
                        Files.createDirectories(newGamePath);

                        for (String preserved : PRESERVED_FILES) {
                            Path oldItem = gameDirectoryPath.resolve(preserved);
                            Path newItem = newGamePath.resolve(preserved);
                            if (Files.exists(oldItem)) {
                                Files.move(oldItem, newItem, StandardCopyOption.REPLACE_EXISTING);
                            }
                        }

                        Platform.runLater(() -> updateStatus("Удаление остатков старого клиента..."));
                        if (Files.exists(gameDirectoryPath)) {
                            deleteRecursively(gameDirectoryPath);
                        }

                        Files.writeString(configFilePath, newBasePath.toAbsolutePath().toString());

                        Platform.runLater(() -> {
                            showCustomConfirmDialog("Путь изменен", "Лаунчер будет перезапущен для применения изменений.");
                            Platform.exit();
                        });

                    } catch (IOException ex) {
                        Platform.runLater(() -> updateStatus("Ошибка при смене пути: " + ex.getMessage()));
                        ex.printStackTrace();
                    }
                }).start();
            }
        }
    }

    private boolean showCustomConfirmDialog(String title, String content) {
        try {
            FXMLLoader loader = new FXMLLoader(HelloApplication.class.getResource("confirm_dialog.fxml"));
            BorderPane dialogRoot = loader.load();
            ConfirmDialogController controller = loader.getController();

            Stage dialogStage = new Stage();
            controller.setStage(dialogStage);
            controller.setData(title, content);

            dialogStage.initStyle(StageStyle.TRANSPARENT);
            dialogStage.initModality(Modality.APPLICATION_MODAL);
            dialogStage.initOwner(rootPane.getScene().getWindow());

            Scene scene = new Scene(dialogRoot);
            scene.setFill(Color.TRANSPARENT);
            dialogStage.setScene(scene);
            dialogStage.showAndWait();
            return controller.isConfirmed();
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    private Path loadGameDirectoryPath() {
        Path defaultConfigPath = Paths.get(System.getProperty("user.home"));
        if (Files.exists(configFilePath)) {
            try {
                String pathStr = Files.readString(configFilePath).trim();
                if (!pathStr.isEmpty()) {
                    return Paths.get(pathStr).resolve(GAME_DIRECTORY_NAME);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return defaultConfigPath.resolve(GAME_DIRECTORY_NAME);
    }

    private void toggleSettingsView(boolean showSettings) {
        settingsView.setVisible(showSettings);
        settingsView.setManaged(showSettings);
        mainView.setVisible(!showSettings);
        mainView.setManaged(!showSettings);
    }

    private void saveRamSetting(int ramValue) {
        try {
            Files.createDirectories(gameDirectoryPath);
            Files.writeString(gameDirectoryPath.resolve("ram.txt"), String.valueOf(ramValue));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private int loadRamSetting() {
        Path ramFile = gameDirectoryPath.resolve("ram.txt");
        if (Files.exists(ramFile)) {
            try {
                return Integer.parseInt(Files.readString(ramFile).trim());
            } catch (IOException | NumberFormatException e) {
                return 4;
            }
        }
        return 4;
    }

    private void checkUpdateAndLaunch() {
        try {
            updateStatus("Проверка целостности клиента...");
            if (!isClientValid()) {
                updateStatus("Обнаружены поврежденные файлы. Восстановление...");
                if (!restoreFromBackup()) {
                    updateStatus("Backup не найден. Полная переустановка...");
                    saveLocalVersion("damaged");
                }
            }

            updateStatus("Проверка обновлений...");
            String remoteVersionUrl = CLIENT_VERSION_URL + "?t=" + System.currentTimeMillis();
            String remoteVersion = downloadTextFile(remoteVersionUrl).trim();
            String localVersion = loadLocalVersion();

            if (remoteVersion.isEmpty()) {
                throw new IOException("Не удалось получить версию клиента с GitHub.");
            }

            boolean needsUpdate = !remoteVersion.equalsIgnoreCase(localVersion) || "damaged".equals(localVersion) || localVersion.isEmpty();

            if (needsUpdate) {
                updateStatus("Скачивание версии " + remoteVersion + "...");
                Path tempZipPath = Files.createTempFile("aurora-client-", ".zip");
                String downloadUrl = String.format(CLIENT_DOWNLOAD_URL_TEMPLATE, remoteVersion);

                try {
                    downloadFile(downloadUrl, tempZipPath, "Скачивание обновления");
                    verifyDownloadedClient(tempZipPath, remoteVersion);
                    saveBackupArchive(tempZipPath, remoteVersion);
                    updateStatus("Установка новой версии...");
                    cleanInstallDirectory();
                    unzip(tempZipPath, gameDirectoryPath);
                    saveLocalVersion(remoteVersion);
                    Files.delete(tempZipPath);
                    updateStatus("Клиент обновлен до версии " + remoteVersion + "!");
                } catch (IOException e) {
                    Files.deleteIfExists(tempZipPath);
                    throw new IOException("Не удалось скачать обновление: " + e.getMessage(), e);
                }
            } else {
                updateStatus("Версия " + localVersion + " актуальна.");
            }

            if (!isJavaDownloaded()) {
                downloadJava();
            }

            updateStatus("Запуск игры...");
            launchGame();

        } catch (Exception e) {
            updateStatus("Ошибка: " + e.getMessage());
            e.printStackTrace();
        } finally {
            Platform.runLater(() -> {
                launchButton.setDisable(false);
                nicknameField.setDisable(false);
                settingsButton.setDisable(false);
            });
        }
    }

    private boolean isClientValid() {
        for (String coreDir : CORE_DIRECTORIES) {
            if (!Files.isDirectory(gameDirectoryPath.resolve(coreDir))) return false;
        }
        if (!Files.exists(gameDirectoryPath.resolve("current_version.txt"))) return false;
        try {
            return findFabricVersion() != null;
        } catch (IOException e) {
            return false;
        }
    }

    private void saveBackupArchive(Path tempZipPath, String version) throws IOException {
        Files.createDirectories(gameDirectoryPath);
        Path backupPath = gameDirectoryPath.resolve(CLIENT_ARCHIVE_NAME);
        Files.copy(tempZipPath, backupPath, StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(gameDirectoryPath.resolve("backup_version.txt"), version);
    }

    private boolean restoreFromBackup() {
        try {
            Path backupPath = gameDirectoryPath.resolve(CLIENT_ARCHIVE_NAME);
            if (!Files.exists(backupPath)) return false;

            String backupVersion = "unknown";
            try {
                backupVersion = Files.readString(gameDirectoryPath.resolve("backup_version.txt")).trim();
            } catch (IOException ignored) {}

            updateStatus("Восстановление из backup (v" + backupVersion + ")...");
            cleanInstallDirectory();
            unzip(backupPath, gameDirectoryPath);
            saveLocalVersion(backupVersion);
            updateStatus("Клиент восстановлен из backup!");
            return true;
        } catch (IOException e) {
            System.err.println("Ошибка при восстановлении из backup: " + e.getMessage());
            return false;
        }
    }

    private void verifyDownloadedClient(Path zipPath, String version) throws IOException, InterruptedException {
        updateStatus("Проверка целостности скачанного файла...");
        String expectedHash = getChecksumForVersion(version);
        if (expectedHash == null) {
            System.out.println("Предупреждение: хэш для версии " + version + " не найден, пропускаем проверку");
            return;
        }
        if (!verifyFileChecksum(zipPath, expectedHash)) {
            Files.deleteIfExists(zipPath);
            throw new IOException("Проверка целостности не пройдена! Файл поврежден.");
        }
        updateStatus("Проверка целостности пройдена");
    }

    private String getChecksumForVersion(String version) throws IOException, InterruptedException {
        String checksumContent = downloadTextFile(CLIENT_CHECKSUM_URL + "?t=" + System.currentTimeMillis());
        for (String line : checksumContent.split("\\R")) {
            String[] parts = line.split(":");
            if (parts.length >= 2 && parts[0].trim().equals(version)) {
                return parts[1].trim();
            }
        }
        return null;
    }

    private boolean verifyFileChecksum(Path filePath, String expectedHash) throws IOException {
        if (!Files.exists(filePath)) return false;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream is = Files.newInputStream(filePath)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    md.update(buffer, 0, read);
                }
            }
            byte[] hashBytes = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString().equalsIgnoreCase(expectedHash);
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("Алгоритм SHA-256 не поддерживается", e);
        }
    }

    private void cleanInstallDirectory() throws IOException {
        if (!Files.exists(gameDirectoryPath)) {
            Files.createDirectories(gameDirectoryPath);
            return;
        }
        try (Stream<Path> stream = Files.list(gameDirectoryPath)) {
            stream.filter(path -> !PRESERVED_FILES.contains(path.getFileName().toString()))
                    .forEach(path -> {
                        try {
                            deleteRecursively(path);
                        } catch (IOException e) {
                            System.err.println("Не удалось удалить " + path + ": " + e.getMessage());
                        }
                    });
        }
    }

    private void deleteRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (Stream<Path> walk = Files.walk(path)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.delete(p); } catch (IOException e) {
                        System.err.println("Не удалось удалить: " + p + " | Причина: " + e.getMessage());
                    }
                });
            }
        } else {
            Files.deleteIfExists(path);
        }
    }

    private void unzip(Path zipFile, Path destDir) throws IOException {
        Files.createDirectories(destDir);
        Path canonicalDestDir = destDir.toRealPath();
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFile))) {
            java.util.zip.ZipEntry zipEntry;
            while ((zipEntry = zis.getNextEntry()) != null) {
                Path resolvedPath = canonicalDestDir.resolve(zipEntry.getName()).normalize();
                if (!resolvedPath.startsWith(canonicalDestDir)) {
                    throw new IOException("Попытка распаковки файла вне целевой директории: " + zipEntry.getName());
                }
                if (zipEntry.isDirectory()) {
                    Files.createDirectories(resolvedPath);
                } else {
                    Files.createDirectories(resolvedPath.getParent());
                    Files.copy(zis, resolvedPath, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }
    }

    private String downloadTextFile(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).header("User-Agent", "AuroraLauncher/1.0").build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IOException("HTTP error: " + response.statusCode());
        return response.body();
    }

    private void launchGame() throws IOException {
        Path javaExecutable = findJavaExecutable();
        if (javaExecutable == null) throw new IOException("Не удалось найти исполняемый файл Java в папке runtime");

        String nickname = nicknameField.getText().trim();
        String classpath = buildClasspath();
        if (classpath.isEmpty()) throw new IOException("Не удалось построить classpath. Папка 'libraries' пуста?");
        String fabricVersion = findFabricVersion();
        if (fabricVersion == null) throw new IOException("Не удалось найти версию Fabric в папке 'versions'");

        String memoryArgument = "-Xmx" + loadRamSetting() + "G";

        List<String> command = new ArrayList<>(Arrays.asList(
                javaExecutable.toAbsolutePath().toString(),
                memoryArgument,
                "-cp", classpath,
                "net.fabricmc.loader.impl.launch.knot.KnotClient",
                "--username", nickname,
                "--version", fabricVersion,
                "--gameDir", gameDirectoryPath.toAbsolutePath().toString(),
                "--assetsDir", gameDirectoryPath.resolve("assets").toAbsolutePath().toString(),
                "--assetIndex", "1.21.5", // Это значение может потребовать обновления в будущем
                "--uuid", "0",
                "--accessToken", "0",
                "--clientId", "",
                "--xuid", "",
                "--userType", "legacy",
                "--versionType", "release"
        ));

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(gameDirectoryPath.toFile());
        processBuilder.inheritIO();
        updateStatus("Запускаем Minecraft...");
        try {
            Process process = processBuilder.start();
            new Thread(() -> {
                try {
                    int exitCode = process.waitFor();
                    Platform.runLater(() -> {
                        if (exitCode == 0) updateStatus("Игра закрыта. Готов к запуску!");
                        else updateStatus("Игра завершилась с ошибкой: " + exitCode);
                    });
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }).start();
        } catch (IOException e) { throw new IOException("Не удалось запустить процесс игры.", e); }
    }

    private String buildClasspath() throws IOException {
        Path librariesDir = gameDirectoryPath.resolve("libraries");
        if (!Files.isDirectory(librariesDir)) return "";
        List<String> classpathEntries;
        try (Stream<Path> stream = Files.walk(librariesDir)) {
            classpathEntries = stream.filter(path -> path.toString().toLowerCase().endsWith(".jar"))
                    .map(Path::toAbsolutePath).map(Path::toString).collect(Collectors.toList());
        }
        String fabricVersion = findFabricVersion();
        if (fabricVersion != null) {
            Path versionJar = gameDirectoryPath.resolve("versions").resolve(fabricVersion).resolve(fabricVersion + ".jar");
            if (Files.exists(versionJar)) classpathEntries.add(versionJar.toAbsolutePath().toString());
        }
        return String.join(File.pathSeparator, classpathEntries);
    }

    private String findFabricVersion() throws IOException {
        Path versionsDir = gameDirectoryPath.resolve("versions");
        if (!Files.isDirectory(versionsDir)) return null;
        try (Stream<Path> stream = Files.list(versionsDir)) {
            return stream.filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.toLowerCase().contains("fabric"))
                    .findFirst().orElse(null);
        }
    }

    private boolean isJavaDownloaded() {
        return Files.exists(javaDirectoryPath) && findJavaExecutable() != null;
    }

    private void downloadJava() throws IOException, InterruptedException {
        updateStatus("Скачиваем Java...");
        Path javaZip = Files.createTempFile("aurora-java-", ".zip");
        downloadFile(JAVA_RUNTIME_URL, javaZip, "Скачивание Java");
        updateStatus("Распаковываем Java...");
        unzip(javaZip, javaDirectoryPath);
        Files.delete(javaZip);
    }

    private void downloadFile(String urlStr, Path targetPath, String statusMessage) throws IOException, InterruptedException {
        Platform.runLater(() -> progressBar.setVisible(true));
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(urlStr)).header("User-Agent", "AuroraLauncher/1.0").build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) throw new IOException("Сервер ответил с ошибкой: " + response.statusCode());
        long totalSize = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(targetPath)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            long downloadedSize = 0;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                downloadedSize += bytesRead;
                if (totalSize > 0) {
                    final double progress = (double) downloadedSize / totalSize;
                    updateProgress(String.format("%s %.0f%%", statusMessage, progress * 100), progress);
                }
            }
        } finally {
            Platform.runLater(() -> progressBar.setVisible(false));
        }
    }

    private Path findJavaExecutable() {
        String os = System.getProperty("os.name").toLowerCase();
        String executableName = os.contains("win") ? "java.exe" : "java";

        Path binDir;
        if (os.contains("mac")) {
            binDir = javaDirectoryPath.resolve("Contents").resolve("Home").resolve("bin");
        } else {
            binDir = javaDirectoryPath.resolve("bin");
        }

        Path javaPath = binDir.resolve(executableName);
        if (Files.exists(javaPath) && Files.isExecutable(javaPath)) {
            return javaPath;
        }

        try (Stream<Path> stream = Files.walk(javaDirectoryPath)) {
            return stream.filter(path -> path.getFileName().toString().equals(executableName) && Files.isExecutable(path))
                    .findFirst().orElse(null);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    private String loadLocalVersion() {
        Path versionFile = gameDirectoryPath.resolve("current_version.txt");
        if (Files.exists(versionFile)) {
            try { return Files.readString(versionFile).trim(); } catch (IOException e) { return ""; }
        }
        return "";
    }

    private void saveLocalVersion(String version) {
        try {
            Files.createDirectories(gameDirectoryPath);
            Files.writeString(gameDirectoryPath.resolve("current_version.txt"), version);
        } catch (IOException e) { e.printStackTrace(); }
    }

    private void saveNickname(String nickname) {
        try {
            Files.createDirectories(gameDirectoryPath);
            Files.writeString(gameDirectoryPath.resolve("nickname.txt"), nickname);
        } catch (IOException e) { e.printStackTrace(); }
    }

    private String loadNickname() {
        Path nicknameFile = gameDirectoryPath.resolve("nickname.txt");
        if (Files.exists(nicknameFile)) {
            try { return Files.readString(nicknameFile).trim(); } catch (IOException e) { return ""; }
        }
        return "";
    }

    private void updateStatus(String text) {
        Platform.runLater(() -> statusLabel.setText(text));
    }

    private void updateProgress(String text, double progress) {
        Platform.runLater(() -> {
            statusLabel.setText(text);
            progressBar.setProgress(progress);
        });
    }
}