package es.upm.grise.prof.plugin;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

@Mojo(name = "detect", defaultPhase = LifecyclePhase.TEST, threadSafe = true)
public class TestSmellsMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project.basedir}", readonly = true, required = true)
    private File baseDir;

    @Parameter(defaultValue = "${project.basedir}/src/test/java")
    private File testsDir;

    @Override
    public void execute() throws MojoExecutionException {
        getLog().info("[testsmells] Ejecutando tsDetect...");
        getLog().info("[testsmells] testsDir=" + testsDir.getAbsolutePath());

        File jarTemp;
        try {
            jarTemp = extractJarFromResources("TestSmellDetector.jar");
        } catch (IOException e) {
            throw new MojoExecutionException("[testsmells] No se pudo extraer el jar del detector", e);
        }

        ProcessBuilder pb = new ProcessBuilder(
                "java", "-jar",
                jarTemp.getAbsolutePath(),
                testsDir.getAbsolutePath()
        );
        pb.directory(baseDir);
        pb.redirectErrorStream(true);

        try {
            Process p = pb.start();

            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    System.out.println(line);
                }
            }

            int code = p.waitFor();
            if (code != 0) {
                getLog().warn("[testsmells] tsDetect terminó con código " + code);
            }

        } catch (Exception e) {
            throw new MojoExecutionException("[testsmells] Error ejecutando tsDetect", e);
        }

        getLog().info("[testsmells] Fin.");
    }

    private File extractJarFromResources(String name) throws IOException, MojoExecutionException {
        InputStream is = getClass().getClassLoader().getResourceAsStream(name);
        if (is == null) {
            throw new MojoExecutionException("[testsmells] No encuentro " + name + " en src/main/resources");
        }

        File temp = Files.createTempFile("tsDetect-", ".jar").toFile();
        temp.deleteOnExit();

        try (OutputStream os = new FileOutputStream(temp)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                os.write(buf, 0, n);
            }
        } finally {
            is.close();
        }
        return temp;
    }
}
