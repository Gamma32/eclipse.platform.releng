/*******************************************************************************
 *  Copyright (c) 2013 IBM Corporation and others.
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  which accompanies this distribution, and is available at
 *  http://www.eclipse.org/legal/epl-v10.html
 * 
 *  Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.releng.tools.pomversion;

import java.io.IOException;
import java.util.HashMap;
import java.util.Stack;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.eclipse.core.filebuffers.FileBuffers;
import org.eclipse.core.filebuffers.ITextFileBuffer;
import org.eclipse.core.filebuffers.ITextFileBufferManager;
import org.eclipse.core.filebuffers.LocationKind;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IResourceDeltaVisitor;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.IEclipsePreferences.PreferenceChangeEvent;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.osgi.util.NLS;
import org.eclipse.releng.tools.RelEngPlugin;
import org.eclipse.ui.texteditor.MarkerUtilities;
import org.osgi.framework.Version;
import org.xml.sax.Attributes;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Validates the content of the pom.xml.  Currently the only check is that the
 * version specified in pom.xml matches the bundle version.
 *
 */
public class PomVersionErrorReporter implements IResourceChangeListener, IEclipsePreferences.IPreferenceChangeListener {

	class PomResourceDeltaVisitor implements IResourceDeltaVisitor {

		public boolean visit(IResourceDelta delta) {
			if (delta != null) {
				IResource resource = delta.getResource();
				switch(resource.getType()) {
					case IResource.PROJECT: {
						//Should we not care about non-plugin projects?
						IProject project = (IProject) resource;
						try {
							if(project.getDescription().hasNature("org.eclipse.pde.PluginNature")) { //$NON-NLS-1$
								if((delta.getFlags() & IResourceDelta.OPEN) > 0) {
									validate(project);
									return false;
								}
								return true;
							}
						}
						catch(CoreException ce) {
							RelEngPlugin.log(ce);
						}
						return false;
 					}
					case IResource.ROOT:
					case IResource.FOLDER: {
						return true;
					}
					case IResource.FILE: {
						switch(delta.getKind()) {
							case IResourceDelta.REMOVED: {
								//if manifest removed, clean up markers
								if(resource.getProjectRelativePath().equals(MANIFEST_PATH)) {
									//manifest content changed
									IProject p = resource.getProject();
									if(p.isAccessible()) {
										cleanMarkers(p);
									}
								}
								break;
							}
							case IResourceDelta.ADDED: {
								//if the POM or manifest has been added scan them
								if(resource.getProjectRelativePath().equals(MANIFEST_PATH) ||
										resource.getProjectRelativePath().equals(POM_PATH)) {
									validate(resource.getProject());
								}
								break;
							}
							case IResourceDelta.CHANGED: {
								//if the content has changed clean + scan
								if((delta.getFlags() & IResourceDelta.CONTENT) > 0) {
									if(resource.getProjectRelativePath().equals(MANIFEST_PATH) ||
											resource.getProjectRelativePath().equals(POM_PATH)) {
										validate(resource.getProject());
									}
								}
								break;
							}
							default: {
								break;
							}
						}
						return false;
					}
				}
			}				
			return false;
		}
	}
	
	/**
	 * XML parsing handler to check the POM version infos
	 */
	class PomVersionHandler extends DefaultHandler {
		private Version bundleVersion;
		private Stack elements = new Stack();
		private boolean checkVersion = false;
		private Locator locator;
		IFile pom = null;
		String severity = null;

		public PomVersionHandler(IFile file, Version bundleVersion, String pref) {
			pom = file;
			severity = pref;
			this.bundleVersion = bundleVersion;
		}

		public void setDocumentLocator(Locator locator) {
			this.locator = locator;
		}

		public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
			if (ELEMENT_VERSION.equals(qName)) {
				if (!elements.isEmpty() && ELEMENT_PROJECT.equals(elements.peek())) {
					checkVersion = true;
				}
			}
			elements.push(qName);
		}

		public void endElement(String uri, String localName, String qName) throws SAXException {
			elements.pop();
		}

		public void characters(char[] ch, int start, int length) throws SAXException {
			if (checkVersion) {
				checkVersion = false;
				// Compare the versions
				String versionString = new String(ch, start, length);
				String origVer = versionString;
				try {
					// Remove snapshot suffix
					int index = versionString.indexOf(SNAPSHOT_SUFFIX);
					if (index >= 0) {
						versionString = versionString.substring(0, index);
					}
					Version pomVersion = Version.parseVersion(versionString);
					// Remove qualifiers and snapshot
					Version bundleVersion2 = new Version(bundleVersion.getMajor(), bundleVersion.getMinor(), bundleVersion.getMicro());
					Version pomVersion2 = new Version(pomVersion.getMajor(), pomVersion.getMinor(), pomVersion.getMicro());

					if (!bundleVersion2.equals(pomVersion2)) {
						String correctedVersion = bundleVersion2.toString();
						if (index >= 0) {
							correctedVersion = correctedVersion.concat(SNAPSHOT_SUFFIX);
						}

						try {
							// Need to create a document to calculate the markers charstart and charend
							IDocument doc = createDocument(pom);
							int lineOffset = doc.getLineOffset(locator.getLineNumber() - 1); // locator lines start at 1
							int linLength = doc.getLineLength(locator.getLineNumber() - 1);
							String str = doc.get(lineOffset, linLength);
							index = str.indexOf(origVer);
							int charStart = lineOffset + index;
							int charEnd = charStart + origVer.length();
							reportMarker(NLS.bind(Messages.PomVersionErrorReporter_pom_version_error_marker_message, pomVersion2.toString(), bundleVersion2.toString()), 
									locator.getLineNumber(), 
									charStart, 
									charEnd, 
									correctedVersion,
									pom,
									severity);
						} catch (BadLocationException e) {
							RelEngPlugin.log(e);
						}
					}
				} catch (IllegalArgumentException e) {
					// Do nothing, user has a bad version
				}
			}
		}
	}
	
	/**
	 * Project relative path to the pom.xml file
	 */
	public static final IPath POM_PATH = new Path("pom.xml"); //$NON-NLS-1$

	/**
	 * Project relative path to the manifest file.
	 */
	public static final IPath MANIFEST_PATH = new Path(JarFile.MANIFEST_NAME);
	private static final String ELEMENT_PROJECT = "project"; //$NON-NLS-1$
	private static final String ELEMENT_VERSION = "version"; //$NON-NLS-1$
	private static final String SNAPSHOT_SUFFIX = "-SNAPSHOT"; //$NON-NLS-1$

	
	/**
	 * Clean up all markers
	 * 
	 * @param project
	 */
	void cleanMarkers(IResource resource) {
		try {
			resource.deleteMarkers(IPomVersionConstants.PROBLEM_MARKER_TYPE, false, IResource.DEPTH_INFINITE);
		}
		catch(CoreException e) {
			RelEngPlugin.log(e);
		}
	}
	
	/**
	 * Validates the version in the Manifest.MF file against the version in the <code>pom.xml</code> file
	 * 
	 * @param project
	 * @param severity
	 */
	public void validate(IProject project) {
		if(project == null || !project.isAccessible()) {
			return;
		}
		//clean up existing markers
		cleanMarkers(project);
		
		String severity = RelEngPlugin.getPlugin().getPreferenceStore().getString(IPomVersionConstants.POM_VERSION_ERROR_LEVEL);
		if (IPomVersionConstants.VALUE_IGNORE.equals(severity)) {
			return;
		}
		IFile manifest = project.getFile(MANIFEST_PATH);
		if(!manifest.exists()) {
			return;
		}
		IFile pom = project.getFile(POM_PATH);
		if(!pom.exists()) {
			return;
		}
		
		// Get the manifest version
		Version bundleVersion = Version.emptyVersion;
		try {
			Manifest mani = new Manifest(manifest.getContents());
			java.util.jar.Attributes attributes = mani.getMainAttributes();
			String ver = attributes.getValue("Bundle-Version"); //$NON-NLS-1$
			if(ver == null) {
				return;
			}
			bundleVersion = new Version(ver);
		} catch (IOException e) {
			RelEngPlugin.log(e);
			return;
		} catch (CoreException e) {
			RelEngPlugin.log(e);
			return;
		}
		// Compare it to the POM file version
		try {
			SAXParserFactory parserFactory = SAXParserFactory.newInstance();
			SAXParser parser = parserFactory.newSAXParser();
			PomVersionHandler handler = new PomVersionHandler(pom, bundleVersion, severity);
			parser.parse(pom.getContents(), handler);
		} catch (Exception e1) {
			// Ignored, if there is a problem with the POM file don't create a marker
		}
	}

	/**
	 * Creates a new POM version problem marker with the given attributes
	 * @param message the message for the marker
	 * @param lineNumber the line number of the problem
	 * @param charStart the starting character offset
	 * @param charEnd the ending character offset
	 * @param correctedVersion the correct version to be inserted
	 * @param pom the handle to the POM file
	 * @param severity the severity of the marker to create
	 */
	void reportMarker(String message, int lineNumber, int charStart, int charEnd, String correctedVersion, IFile pom, String severity) {
		try {
			HashMap attributes = new HashMap();
			attributes.put(IMarker.MESSAGE, message);
			if (severity.equals(IPomVersionConstants.VALUE_WARNING)){
				attributes.put(IMarker.SEVERITY, new Integer(IMarker.SEVERITY_WARNING));
			} else {
				attributes.put(IMarker.SEVERITY, new Integer(IMarker.SEVERITY_ERROR));
			}
			if (lineNumber == -1) {
				lineNumber = 1;
			}
			attributes.put(IMarker.LINE_NUMBER, new Integer(lineNumber));
			attributes.put(IMarker.CHAR_START, new Integer(charStart));
			attributes.put(IMarker.CHAR_END, new Integer(charEnd));
			attributes.put(IPomVersionConstants.POM_CORRECT_VERSION, correctedVersion);
			MarkerUtilities.createMarker(pom, attributes, IPomVersionConstants.PROBLEM_MARKER_TYPE);
		} catch (CoreException e){
			RelEngPlugin.log(e);
		}
	}

	/**
	 * Creates a new {@link IDocument} for the given {@link IFile}. <code>null</code>
	 * is returned if the {@link IFile} does not exist or the {@link ITextFileBufferManager}
	 * cannot be acquired or there was an exception trying to create the {@link IDocument}.
	 * 
	 * @param file
	 * @return a new {@link IDocument} or <code>null</code>
	 */
	protected IDocument createDocument(IFile file) {
		if (!file.exists()) {
			return null;
		}
		ITextFileBufferManager manager = FileBuffers.getTextFileBufferManager();
		if (manager == null) {
			return null;
		}
		try {
			manager.connect(file.getFullPath(), LocationKind.NORMALIZE, null);
			ITextFileBuffer textBuf = manager.getTextFileBuffer(file.getFullPath(), LocationKind.NORMALIZE);
			IDocument document = textBuf.getDocument();
			manager.disconnect(file.getFullPath(), LocationKind.NORMALIZE, null);
			return document;
		} catch (CoreException e) {
			RelEngPlugin.log(e);
		}
		return null;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.core.resources.IResourceChangeListener#resourceChanged(org.eclipse.core.resources.IResourceChangeEvent)
	 */
	public void resourceChanged(IResourceChangeEvent event) {
		IResourceDelta delta = event.getDelta();
		if(delta != null) {
			final PomResourceDeltaVisitor visitor = new PomResourceDeltaVisitor();
			try {
				delta.accept(visitor);
			} catch (CoreException e) {
				RelEngPlugin.log(e);
			}
		}
	}

	/* (non-Javadoc)
	 * @see org.eclipse.core.runtime.preferences.IEclipsePreferences.IPreferenceChangeListener#preferenceChange(org.eclipse.core.runtime.preferences.IEclipsePreferences.PreferenceChangeEvent)
	 */
	public void preferenceChange(PreferenceChangeEvent event) {
		if(IPomVersionConstants.POM_VERSION_ERROR_LEVEL.equals(event.getKey())) {
			final String severity = (String) event.getNewValue();
			if(severity != null) {
				if(IPomVersionConstants.VALUE_IGNORE.equals(severity)) {
					//we turned it off
					ResourcesPlugin.getWorkspace().removeResourceChangeListener(this);
				}
				else if(IPomVersionConstants.VALUE_IGNORE.equals(event.getOldValue())) {
					// we turned it on
					ResourcesPlugin.getWorkspace().addResourceChangeListener(this, IResourceChangeEvent.POST_BUILD);
				}
				IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
				IProject[] projects = root.getProjects();
				for (int i = 0; i < projects.length; i++) {
					validate(projects[i]);
				}
			}
		}
	}
}
