/*
 * Copyright (C) 2009 Sonatype, Inc.                                                                                                                          
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses/.
 *
 */
package org.apache.maven.plugin.repository.collector;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.versioning.InvalidVersionSpecificationException;
import org.apache.maven.artifact.versioning.VersionRange;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.DependencyManagement;
import org.apache.maven.model.Extension;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginManagement;
import org.apache.maven.model.ReportPlugin;
import org.apache.maven.model.Reporting;
import org.apache.maven.plugin.Mojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.DefaultMavenProjectBuilder;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.artifact.InvalidDependencyVersionException;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public abstract class AbstractCollectorMojo
    implements Mojo
{

    /**
     * @parameter default-value="${project}"
     * @required
     * @readonly
     */
    private MavenProject project;

    /**
     * @parameter expression="${collector.includeDependencies}" default-value="true"
     */
    private boolean includeDependencies;

    /**
     * @parameter expression="${collector.includePlugins}" default-value="true"
     */
    private boolean includePlugins;

    /**
     * @parameter expression="${collector.includeReportPlugins}" default-value="true"
     */
    private boolean includeReportPlugins;

    /**
     * @parameter expression="${collector.includeExtensions}" default-value="true"
     */
    private boolean includeExtensions;

    /**
     * @parameter expression="${collector.includeDependencyManagement}" default-value="true"
     */
    private boolean includeDependencyManagement;

    /**
     * @parameter expression="${collector.includePluginManagement}" default-value="true"
     */
    private boolean includePluginManagement;

    /**
     * @parameter default-value="${localRepository}"
     * @readonly
     * @required
     */
    private ArtifactRepository localRepository;

    /**
     * @parameter default-value="${plugin.pluginArtifact}"
     * @readonly
     * @required
     */
    private Artifact myArtifact;

    /**
     * @component
     */
    private ArtifactFactory artifactFactory;

    /**
     * @component
     */
    private MavenProjectBuilder mavenProjectBuilder;

    private Log log;

    public void execute()
        throws MojoExecutionException, MojoFailureException
    {
        Map<String, Map<String, Artifact>> pluginManagedVersions = new HashMap<String, Map<String, Artifact>>();
        Set<Artifact> artifacts = assembleDirectArtifacts( pluginManagedVersions );

        MavenProject collectorProject = buildProject( artifacts );

        collect( collectorProject, pluginManagedVersions );
    }

    @SuppressWarnings( "unchecked" )
    protected void invalidateProjectBuilderCache()
        throws MojoExecutionException
    {
        Class klass = DefaultMavenProjectBuilder.class;

        try
        {
            Field field;
            try
            {
                field = klass.getDeclaredField( "processedProjectCache" );
                field.setAccessible( true );

                Object cache = field.get( mavenProjectBuilder );
                cache.getClass().getDeclaredMethod( "clear", (Class<?>[]) null ).invoke( cache, (Object[]) null );

                field.setAccessible( false );
            }
            catch ( NoSuchFieldException e )
            {
                // fine... no field, no cache. we'll ignore it.
            }

            try
            {
                field = klass.getDeclaredField( "rawProjectCache" );
                field.setAccessible( true );

                Object cache = field.get( mavenProjectBuilder );
                cache.getClass().getDeclaredMethod( "clear", (Class<?>[]) null ).invoke( cache, (Object[]) null );

                field.setAccessible( false );
            }
            catch ( NoSuchFieldException e )
            {
                // fine... no field, no cache. we'll ignore it.
            }
        }
        catch ( IllegalArgumentException e )
        {
            throw new MojoExecutionException( "Failed to invalidate project-builder cache: " + e.getMessage(), e );
        }
        catch ( IllegalAccessException e )
        {
            throw new MojoExecutionException( "Failed to invalidate project-builder cache: " + e.getMessage(), e );
        }
        catch ( SecurityException e )
        {
            throw new MojoExecutionException( "Failed to invalidate project-builder cache: " + e.getMessage(), e );
        }
        catch ( InvocationTargetException e )
        {
            throw new MojoExecutionException( "Failed to invalidate project-builder cache: " + e.getMessage(), e );
        }
        catch ( NoSuchMethodException e )
        {
            throw new MojoExecutionException( "Failed to invalidate project-builder cache: " + e.getMessage(), e );
        }
    }

    protected abstract void collect( MavenProject project, Map<String, Map<String, Artifact>> pluginManagedVersions )
        throws MojoExecutionException;

    private MavenProject buildProject( final Set<Artifact> artifacts )
    {
        Model m = new Model();
        m.setModelVersion( "4.0.0" );

        m.setGroupId( project.getGroupId() );
        m.setArtifactId( project.getArtifactId() );
        m.setVersion( project.getVersion() );

        List<Dependency> deps = new ArrayList<Dependency>( artifacts.size() );
        for ( Artifact a : artifacts )
        {
            Dependency d = new Dependency();

            d.setArtifactId( a.getArtifactId() );
            d.setGroupId( a.getGroupId() );
            d.setVersion( a.getVersion() );
            d.setType( a.getType() );
            d.setScope( a.getScope() );
            d.setClassifier( a.getClassifier() );

            if ( Artifact.SCOPE_SYSTEM.equals( a.getScope() ) )
            {
                d.setSystemPath( a.getFile().getAbsolutePath() );
            }

            deps.add( d );
        }

        m.setDependencies( deps );

        MavenProject tmpProject = new MavenProject( m );

        tmpProject.setDependencyArtifacts( artifacts );

        tmpProject.setRemoteArtifactRepositories( project.getRemoteArtifactRepositories() );
        tmpProject.setArtifact( project.getArtifact() );

        return tmpProject;
    }

    private Set<Artifact> assembleDirectArtifacts( final Map<String, Map<String, Artifact>> pluginManagedVersions )
        throws MojoExecutionException
    {
        Set<Artifact> artifacts = new LinkedHashSet<Artifact>();
        Set<String> ids = new HashSet<String>();

        assembleDependencyArtifacts( artifacts, ids );
        assembleExtensionArtifacts( artifacts, ids );
        assemblePluginArtifacts( artifacts, ids, pluginManagedVersions );

        return artifacts;
    }

    private void assembleExtensionArtifacts( final Set<Artifact> artifacts, final Set<String> ids )
        throws MojoExecutionException
    {
        if ( includeExtensions )
        {
            List<Extension> extensions = project.getModel().getBuild().getExtensions();
            if ( extensions != null )
            {
                for ( Extension ext : extensions )
                {
                    String id = getManagementKey( ext );
                    if ( !ids.contains( id ) )
                    {
                        ids.add( id );

                        VersionRange vr;
                        try
                        {
                            vr = VersionRange.createFromVersionSpec( ext.getVersion() );
                        }
                        catch ( InvalidVersionSpecificationException e )
                        {
                            throw new MojoExecutionException( "While creating artifact from reporting plugin: " + id
                                + ":" + e.getMessage(), e );
                        }

                        Artifact a =
                            artifactFactory.createExtensionArtifact( ext.getGroupId(), ext.getArtifactId(), vr );
                        artifacts.add( a );
                    }
                }
            }
        }
    }

    private void assemblePluginArtifacts( final Set<Artifact> artifacts, final Set<String> ids,
                                          final Map<String, Map<String, Artifact>> pluginManagedVersions )
        throws MojoExecutionException
    {
        if ( includePlugins )
        {
            List<Plugin> plugins = project.getModel().getBuild().getPlugins();
            if ( plugins != null )
            {
                addPlugins( artifacts, ids, plugins, pluginManagedVersions, "build plugins" );
            }
        }

        if ( includeReportPlugins )
        {
            Reporting reporting = project.getModel().getReporting();
            if ( reporting != null && reporting.getPlugins() != null )
            {
                for ( ReportPlugin p : reporting.getPlugins() )
                {
                    String id = getManagementKey( p );
                    if ( !ids.contains( id ) )
                    {
                        ids.add( id );

                        VersionRange vr;
                        try
                        {
                            vr = VersionRange.createFromVersionSpec( p.getVersion() );
                        }
                        catch ( InvalidVersionSpecificationException e )
                        {
                            throw new MojoExecutionException( "While creating artifact from reporting plugin: " + id
                                + ":" + e.getMessage(), e );
                        }

                        Artifact a = artifactFactory.createPluginArtifact( p.getGroupId(), p.getArtifactId(), vr );
                        artifacts.add( a );
                    }
                }
            }
        }

        if ( includePluginManagement )
        {
            PluginManagement pm = project.getModel().getBuild().getPluginManagement();
            if ( pm != null && pm.getPlugins() != null )
            {
                addPlugins( artifacts, ids, pm.getPlugins(), pluginManagedVersions, "plugin-management" );
            }
        }
    }

    @SuppressWarnings( "unchecked" )
    private void assembleDependencyArtifacts( final Set<Artifact> artifacts, final Set<String> ids )
        throws MojoExecutionException
    {
        if ( includeDependencies )
        {
            try
            {
                artifacts.addAll( project.createArtifacts( artifactFactory, Artifact.SCOPE_TEST, null ) );
            }
            catch ( InvalidDependencyVersionException e )
            {
                throw new MojoExecutionException( "While creating project dependency artifacts: " + e.getMessage(), e );
            }

            for ( Artifact a : artifacts )
            {
                ids.add( a.getDependencyConflictId() );
                if ( !Artifact.SCOPE_SYSTEM.equals( a.getScope() ) )
                {
                    a.setFile( null );
                    a.setResolved( false );
                }
                else
                {
                    a.setResolved( true );
                }
            }
        }

        if ( includeDependencyManagement )
        {
            DependencyManagement dm = project.getModel().getDependencyManagement();
            if ( dm != null && dm.getDependencies() != null )
            {
                addDependencies( artifacts, ids, dm.getDependencies(), "dependency-management" );
            }
        }
    }

    @SuppressWarnings( "unchecked" )
    private void addPlugins( final Set<Artifact> artifacts, final Set<String> collectedIds, final List<Plugin> plugins,
                             final Map<String, Map<String, Artifact>> pluginManagedVersions, final String location )
        throws MojoExecutionException
    {
        String depLocation = location + " (plugin-level dependency)";

        for ( Plugin p : plugins )
        {
            String id = getManagementKey( p );
            if ( !collectedIds.contains( id ) && !myArtifact.getDependencyConflictId().equals( id ) )
            {
                if ( p.getDependencies() != null )
                {
                    addDependencies( artifacts, collectedIds, p.getDependencies(), depLocation );
                }

                collectedIds.add( id );

                VersionRange vr;
                try
                {
                    vr = VersionRange.createFromVersionSpec( p.getVersion() );
                }
                catch ( InvalidVersionSpecificationException e )
                {
                    throw new MojoExecutionException( "While creating artifact from " + location + ": " + id + ":"
                        + e.getMessage(), e );
                }

                Artifact a = artifactFactory.createPluginArtifact( p.getGroupId(), p.getArtifactId(), vr );
                artifacts.add( a );

                try
                {
                    MavenProject pluginProject =
                        mavenProjectBuilder.buildFromRepository( a, project.getRemoteArtifactRepositories(),
                                                                 localRepository );

                    if ( pluginProject != null )
                    {
                        pluginManagedVersions.put( id, pluginProject.getManagedVersionMap() );
                    }
                }
                catch ( ProjectBuildingException e )
                {
                    throw new MojoExecutionException( "Cannot retrieve plugin project from repository: "
                        + e.getMessage(), e );
                }
            }
        }
    }

    protected String getManagementKey( final Plugin p )
    {
        return p.getGroupId() + ":" + p.getArtifactId() + ":maven-plugin";
    }

    protected String getManagementKey( final ReportPlugin p )
    {
        return p.getGroupId() + ":" + p.getArtifactId() + ":maven-plugin";
    }

    protected String getManagementKey( final Extension e )
    {
        return e.getGroupId() + ":" + e.getArtifactId() + ":jar";
    }

    private void addDependencies( final Set<Artifact> artifacts, final Set<String> collectedIds,
                                  final List<Dependency> dependencies, final String location )
        throws MojoExecutionException
    {
        for ( Dependency d : dependencies )
        {
            String id = d.getManagementKey();
            if ( !collectedIds.contains( id ) )
            {
                collectedIds.add( id );

                VersionRange vr;
                try
                {
                    vr = VersionRange.createFromVersionSpec( d.getVersion() );
                }
                catch ( InvalidVersionSpecificationException e )
                {
                    throw new MojoExecutionException( "While creating artifact from " + location + ": "
                        + d.getManagementKey() + ":" + e.getMessage(), e );
                }

                Artifact a =
                    artifactFactory.createDependencyArtifact( d.getGroupId(), d.getArtifactId(), vr, d.getType(),
                                                              d.getClassifier(), d.getScope() );

                if ( Artifact.SCOPE_SYSTEM.equals( d.getScope() ) )
                {
                    a.setFile( new File( d.getSystemPath() ) );
                }

                artifacts.add( a );
            }
        }
    }

    public Log getLog()
    {
        return log;
    }

    public void setLog( final Log log )
    {
        this.log = log;
    }

    public MavenProject getProject()
    {
        return project;
    }

    public void setProject( final MavenProject project )
    {
        this.project = project;
    }

    public boolean isIncludeDependencies()
    {
        return includeDependencies;
    }

    public void setIncludeDependencies( final boolean includeDependencies )
    {
        this.includeDependencies = includeDependencies;
    }

    public boolean isIncludePlugins()
    {
        return includePlugins;
    }

    public void setIncludePlugins( final boolean includePlugins )
    {
        this.includePlugins = includePlugins;
    }

    public boolean isIncludeReportPlugins()
    {
        return includeReportPlugins;
    }

    public void setIncludeReportPlugins( final boolean includeReportPlugins )
    {
        this.includeReportPlugins = includeReportPlugins;
    }

    public boolean isIncludeExtensions()
    {
        return includeExtensions;
    }

    public void setIncludeExtensions( final boolean includeExtensions )
    {
        this.includeExtensions = includeExtensions;
    }

    public boolean isIncludeDependencyManagement()
    {
        return includeDependencyManagement;
    }

    public void setIncludeDependencyManagement( final boolean includeDependencyManagement )
    {
        this.includeDependencyManagement = includeDependencyManagement;
    }

    public boolean isIncludePluginManagement()
    {
        return includePluginManagement;
    }

    public void setIncludePluginManagement( final boolean includePluginManagement )
    {
        this.includePluginManagement = includePluginManagement;
    }

    public ArtifactFactory getArtifactFactory()
    {
        return artifactFactory;
    }

    public void setArtifactFactory( final ArtifactFactory artifactFactory )
    {
        this.artifactFactory = artifactFactory;
    }

    public Artifact getMyArtifact()
    {
        return myArtifact;
    }

    public void setMyArtifact( final Artifact myArtifact )
    {
        this.myArtifact = myArtifact;
    }

    public MavenProjectBuilder getMavenProjectBuilder()
    {
        return mavenProjectBuilder;
    }

    public void setMavenProjectBuilder( final MavenProjectBuilder mavenProjectBuilder )
    {
        this.mavenProjectBuilder = mavenProjectBuilder;
    }

}
