/*
 * Copyright (c) 2013-2015, Nikita Lipsky, Excelsior LLC.
 *
 *  Java ReStart is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  Java ReStart is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with Java ReStart.  If not, see <http://www.gnu.org/licenses/>.
 *
*/
package javarestart;

import org.json.simple.JSONObject;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Stream;

/**
 * @author Nikita Lipsky
 */
public class WebClassLoader extends URLClassLoader {

    private URL baseURL;

    private final JSONObject descriptor;

    private List<ClassLoaderListener> listeners;

    private HashMap<String, byte[]> initialBundle = new HashMap<>();

    // Workaround for javafx.scene.media.AudioClip limitation.
    // It only supports http:// and file:// protocols,
    // so we convert java:// and wfx:// URLs to http:// to let .wav resources to be played
    // by AudioClip.
    // TODO: fix the limitation and contribute it to OpenJDK
    private boolean change4WavToHttp;

    private WebClassLoader(URL url, JSONObject desc, boolean baseURL) throws IOException {
        super(new URL[0], Thread.currentThread().getContextClassLoader());
        this.baseURL = baseURL? url : new URL(url, (String) desc.get("root"));
        final String protocol = this.baseURL.getProtocol();
        this.change4WavToHttp = Stream.of("java", "wfx").anyMatch(protocol::equals);
        this.descriptor = desc;
        preLoadInitial();
    }

    private void preLoadInitial() {
        Thread thread = new Thread() {
            @Override
            public void run() {
                try {
                    InputStream in =  new URL(baseURL.getProtocol(),
                            baseURL.getHost(),
                            baseURL.getPort(),
                            baseURL.getPath() + "?bundle=initial").openStream();
                    while(true) {
                        int nameLength = Integer.parseInt(Utils.readAsciiLine(in), 16);
                        if (nameLength == 0) {
                            return;
                        }
                        String name = Utils.readAsciiLine(in); //TODO: name can be not Ascii
                        int resLength = Integer.parseUnsignedInt(Utils.readAsciiLine(in), 16);
                        if (resLength != -1) {
                            final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                            Utils.copy(in, buffer, resLength);
                            initialBundle.put(name, buffer.toByteArray());
                            Utils.readAsciiLine(in);
                        } else {
                            initialBundle.put(name, null);
                        }
                    }

                } catch (Throwable e) {
                    e.printStackTrace();
                }
            }
        };
        thread.setDaemon(true);
        thread.start();
    }

    WebClassLoader(URL url, JSONObject desc) throws IOException{
        this(url, desc, false);
    }

    public void addListener(ClassLoaderListener listener) {
        if (listeners == null) {
            listeners = new LinkedList<>();
        }
        listeners.add(listener);
    }

    private void fireClassLoaded(String classname) {
        if (listeners == null) return;
        for (ClassLoaderListener l: listeners) {
            l.classLoaded(classname);
        }
    }

    private Class<?> tryToLoadClass(InputStream in) throws IOException {
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Utils.copy(in, buffer);
        buffer.flush();

        byte buf[] = buffer.toByteArray();
        return defineClass(null, buf, 0, buf.length);
    }

    @Override
    protected Class<?> findClass(final String name) throws ClassNotFoundException {
        String res = name.replace('.', '/') + ".class";
        try {
            if (initialBundle.containsKey(res)) {
                byte[] classBytes = initialBundle.get(res);
                initialBundle.remove(res);
                Class c = defineClass(null, classBytes, 0, classBytes.length);
                fireClassLoaded(name);
                return c;
            }
            Class c = tryToLoadClass(findResourceImpl(name.replace('.', '/') + ".class").openStream());
            fireClassLoaded(name);
            return c;
        } catch (final Exception e) {
            throw new ClassNotFoundException(e.getMessage(), e);
        }
    }

    private URL checkResourceExists(URL url) {
        try {
            url.openStream().close();
            return url;
        } catch (Exception e) {
            return null;
        }
    }

    private URL findResourceImpl(final String name) {
        boolean handleWavSpecially = change4WavToHttp && name.endsWith(".wav");
        if (handleWavSpecially && initialBundle.containsKey(name)) {
            int dot = name.lastIndexOf(".");
            String resName;
            String ext;
            if (dot == -1) {
                resName = name;
                ext = "";
            } else {
                resName = name.substring(0, dot);
                ext = name.substring(dot + 1);
            }
            int slash = resName.lastIndexOf("/");
            if (slash != -1) {
                resName = resName.substring(slash + 1);
            }
            File f = Utils.fetchResourceToTempFile(resName, ext, new ByteArrayInputStream(initialBundle.get(name)));
            try {
                return f.toURI().toURL();
            } catch (MalformedURLException e) {
                e.printStackTrace();
            }
        }
        try {
            return new URL(
                    //see comment above for this hack explanation
                    handleWavSpecially? "http" : baseURL.getProtocol(),
                    baseURL.getHost(),
                    baseURL.getPort(),
                    baseURL.getPath() + '/' + name);
        } catch (final MalformedURLException e) {
            return null;
        }
    }

    @Override
    public URL findResource(final String name) {
        if (initialBundle.containsKey(name)) {
            return initialBundle.get(name) == null? null: findResourceImpl(name);
        }
        return checkResourceExists(findResourceImpl(name));
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        if (initialBundle.containsKey(name)) {
            byte[] bytes = initialBundle.get(name);
            return bytes == null? null: new ByteArrayInputStream(bytes);
        }
        return super.getResourceAsStream(name);
    }

    @Override
    protected String findLibrary(final String libname) {
        // TODO: nix/mac/solaris?
        File temp = Utils.fetchResourceToTempFile(libname, ".dll", findResource(libname + ".dll"));
        return temp ==  null? null: temp.getAbsolutePath();
    }

    private String getDescField(final String field) {
        return (String) descriptor.get(field);
    }

    public URL getFxml() {
        return getResource(getDescField("fxml"));
    }

    public URL getSplash() {
        return getResource(getDescField("splash"));
    }

    public Class<?> getMain() throws ClassNotFoundException {
        return loadClass(getDescField("main"));
    }

    public URL getBaseURL() {
        return baseURL;
    }

    private String resourceName(URL url) {
        String burl = baseURL.toExternalForm();
        String eurl = url.toExternalForm();
        int length = burl.endsWith("/") ? burl.length() : burl.length() + 1;
        return eurl.length()<=length? "" : eurl.substring(length);
    }

    public boolean isPreLoaded(URL url) {
        return initialBundle.containsKey(resourceName(url));
    }

    public InputStream getPreLoadedResourceAsStream(URL url) throws IOException {
        String name = resourceName(url);
        byte[] bytes = initialBundle.get(name);
        if (bytes == null) {
            throw new FileNotFoundException("Resource not found: " + url.toExternalForm());
        }
        return new ByteArrayInputStream(bytes);
    }

    public long getPreLoadedResourceLength(URL url) {
        byte[] bytes = initialBundle.get(resourceName(url));
        return bytes == null? -1 : bytes.length;
    }
}
