---
layout: default
title: CSS & JavaScript - User Interface Customization - CAS
category: User Interface
---

{% include variables.html %}

# CSS

The default styles are all contained in one single file located in `src/main/resources/static/css/cas.css`. This 
location is set in `cas-theme-default.properties`. CAS by default uses [Material.io](https://material.io/) library 
and design specification as a base for its user experience.

If you would like to create your own `css/custom.css file`, for example, you will need to update `cas.standard.css.file` key in that file.

```bash
cas.standard.css.file=/css/cas.css
cas.standard.js.file=/js/cas.js
```

## Responsive Design

CSS media queries bring responsive design features to CAS which would allow the adopter to focus 
on one theme for all appropriate devices and platforms. These queries are defined in the 
same `cas.css` file. They follow the Twitter Bootstrap breakpoints and grid.

# Javascript

If you need to add some JavaScript, feel free to append `src/main/resources/static/js/cas.js`.

You can also create your own `custom.js` file, for example, and call it from within `scripts.html` like so:

```html
<script type="text/javascript" src="/js/custom.js"></script>
```

If you are developing themes per service, each theme also has the ability 
to specify a custom `cas.js` file under the `cas.standard.js.file` setting.

Most importantly, the following Javascript libraries are utilized by CAS automatically:

* [JQuery](https://jquery.com/)
* [Bootstrap for grid / flex utilities](https://getbootstrap.com/docs/4.5/getting-started/contents/#css-files)
* [Material.io](https://material.io/)

## Script Loading

CAS provides a callback function that allows adopters to be notified when script 
loading has completed and this would be a safe time to execute/load other Javascript-related 
functions that depend on JQuery inside the actual page.

```javascript
function jqueryReady() {
    // Custom Javascript tasks can be carried out now via JQuery...
}
```

## Checking CAPSLOCK

CAS will display a brief warning when the CAPSLOCK key is turned on during the typing 
of the credential password. This check is enforced by the `cas.js` file.

## Browser Cookie Support

For CAS to honor a single sign-on session, the browser MUST support and accept cookies. CAS will notify the
user if the browser has turned off its support for cookies. This behavior is controlled via the `cas.js` file.

## Preserving Anchor Fragments

Anchors/fragments may be lost across redirects as the server-side handler of the form post 
ignores the client-side anchor, unless appended to the form POST url. This is needed if you 
want a CAS-authenticated application to be able to use anchors/fragments when bookmarking. CAS 
is configured by default to preserve anchor fragments where and when specified. There is 
nothing further for you to do.

### WebJARs for Javascript/CSS Libraries

The CAS application packages third party static resources inside the CAS webapp rather 
than referencing CDN links so that CAS may be deployed on 
networks with limited internet access.

The Third party static resources are packaged in "WebJAR" jar files and served up via the servlet `3.0` feature 
that merges any folders under `META-INF/resources` in web application jars with the application's web root.

For developers modifying CAS, if adding or modifying a 3rd party library, the steps are:

- Add WebJAR dependency to `dependencies.gradle` in the `ext.library.webjars` section.
- Add dependency version to `gradle.properties` and use it in `dependency.gradle`.
- Add entry to `core/cas-server-core-web/src/main/resources/cas_common_messages.properties` for each resource (e.g. js or css). 
- Reference the version from `gradle.properties` in the URL and it will be filtered in at build time).

<div class="alert alert-info">:information_source: <strong>Resource Caching</strong><p>The build attempts to 
rebuild all relevant modules again when version numbers change and resources upgraded. If
you do need to forcefully remove cached artifacts and repackage the application anew, run 
the build's <code>clean</code> task inside the <code>core/cas-server-core-web</code> module.</p></div>

For example:

```properties
webjars.zxcvbn.js=/webjars/zxcvbn/dist/zxcvbn.js
```

Then Reference the entry from `cas_common_messages.properties` in the relevant view (i.e HTML page) where the entry is `webjars.zxcvbn.js`:

```html
<script type="text/javascript" th:src="@{#{webjars.zxcvbn.js}}"></script>
```
     
Please note that CAS allows you to use version agnostic URLs for Webjars. Using jQuery as an example, 
accessing `/webjars/jquery/jquery.min.js` results in `/webjars/jquery/x.y.z/jquery.min.js` 
where `x.y.z` is the webjar version.

#### Building WebJARs

You can search for webjars at http://webjars.org. There are three flavors of WebJARs that you 
can read about but the NPM and Bower types can be created automatically for any version 
(if they don't already exist) as long as there exists an NPM or Bower package for the 
web resources you want to use. Click the "Add a webjar" button and follow 
the instructions. If customizing the UI in an overlay, the deployer can add webjars as 
dependencies to their overlay project and reference the URLs of the resource either directly 
in an html file or via adding an entry to a `common_messages.properties` file 
in the overlay project's `src\main\resources` folder.
