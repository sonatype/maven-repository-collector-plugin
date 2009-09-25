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

import org.apache.maven.ProjectDependenciesResolver;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.metadata.ArtifactMetadataSource;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.ArtifactRepositoryPolicy;
import org.apache.maven.artifact.repository.DefaultArtifactRepository;
import org.apache.maven.artifact.repository.layout.DefaultRepositoryLayout;
import org.apache.maven.artifact.resolver.ArtifactNotFoundException;
import org.apache.maven.artifact.resolver.ArtifactResolutionException;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingException;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;

/**
 * Resolve, but DO NOT COPY, all artifacts used to build a project. These artifacts should be available
 * in the local repository after this mojo executes.
 * 
 * @goal resolve
 */
public class ResolveMojo
    extends AbstractCollectorMojo
{
    
    /**
     * @parameter default-value="${session}"
     * @required
     * @readonly
     */
    private MavenSession session;

    /**
     * @parameter expression="${collector.localRepositoryProperty}"
     */
    private String localRepositoryProperty;

    /**
     * @parameter expression="${collector.localRepositoryDirectory}"
     */
    private File localRepositoryDirectory;

    /**
     * @parameter expression="${collector.bundleFile}"
     */
    private File bundleFile;

    /**
     * @parameter expression="${collector.resolveFromExistingLocalRepo}" default-value="true"
     */
    private boolean resolveFromExistingLocalRepo;

    /**
     * @parameter expression="${collector.dedupe}" default-value="true"
     */
    private boolean dedupe;

    /**
     * @component
     */
    private ProjectDependenciesResolver projectResolver;

    /**
     * @component
     */
    private ArtifactResolver artifactResolver;

    /**
     * @component
     */
    private ArtifactMetadataSource metadataSource;

    @SuppressWarnings( "unchecked" )
    @Override
    protected void collect( final MavenProject project, final Map<String, Map<String, Artifact>> pluginManagedVersions )
        throws MojoExecutionException
    {
        injectLocalAsRemotes( project );
        invalidateProjectBuilderCache();

        MavenSession selectedSession = selectSession();

        getLog().info( "Resolving artifacts to: " + selectedSession.getLocalRepository().getUrl() );

        Set<String> scopes = new HashSet<String>();
        scopes.add( Artifact.SCOPE_TEST );
        scopes.add( Artifact.SCOPE_RUNTIME );
        
        Set<Artifact> result = null;
        try
        {
            if ( dedupe )
            {
                result = projectResolver.resolve( project, scopes, selectedSession );
            }
            else
            {
                getLog().info( "Resolving " + project.getDependencyArtifacts().size() + " artifacts." );

                for ( Artifact artifact : (Set<Artifact>) project.getDependencyArtifacts() )
                {
                    getLog().debug( "Resolving: " + artifact.getId() );

                    Map<String, Artifact> managed = pluginManagedVersions.get( artifact.getDependencyConflictId() );
                    if ( managed == null )
                    {
                        managed = getProject().getManagedVersionMap();
                    }

                    artifactResolver.resolveTransitively( Collections.singleton( artifact ), project.getArtifact(),
                                                          managed, selectedSession.getLocalRepository(),
                                                          project.getRemoteArtifactRepositories(), metadataSource );

                    getMavenProjectBuilder().buildFromRepository( artifact, project.getRemoteArtifactRepositories(),
                                                                  selectedSession.getLocalRepository() );
                }

                result = project.getArtifacts();
            }
        }
        catch ( ArtifactResolutionException e )
        {
            throw new MojoExecutionException( "Failed to resolve project artifacts: " + e.getMessage(), e );
        }
        catch ( ArtifactNotFoundException e )
        {
            throw new MojoExecutionException( "Failed to resolve project artifacts: " + e.getMessage(), e );
        }
        catch ( ProjectBuildingException e )
        {
            throw new MojoExecutionException( "Failed to resolve project artifacts: " + e.getMessage(), e );
        }
        
        if ( getProject().getParentArtifact() != null )
        {
            Artifact parentPomArtifact = getProject().getParentArtifact();
            parentPomArtifact =
                getArtifactFactory().createProjectArtifact( parentPomArtifact.getGroupId(),
                                                            parentPomArtifact.getArtifactId(),
                                                            parentPomArtifact.getVersion() );

            try
            {
                getMavenProjectBuilder().buildFromRepository( parentPomArtifact,
                                                              project.getRemoteArtifactRepositories(),
                                                              selectedSession.getLocalRepository() );
            }
            catch ( ProjectBuildingException e )
            {
                getLog().debug(
                                "Failed to resolve parent POM: " + getProject().getParentArtifact().getId()
                                    + ", continuing (it may be reachable on disk)." );
            }
        }

        if ( result != null && !result.isEmpty() )
        {
            List<Artifact> sorted = new ArrayList<Artifact>( result );
            Collections.sort( sorted, new Comparator<Artifact>()
            {
                public int compare( final Artifact a1, final Artifact a2 )
                {
                    return a1.getArtifactId().compareTo( a2.getArtifactId() );
                }
            } );
            
            StringBuilder builder = new StringBuilder();
            builder.append( sorted.size() ).append( " artifacts resolved:\n\n" );
            for ( Artifact a : sorted )
            {
                builder.append( "\n- " ).append( a.getArtifactId() ).append( " (" ).append( a.getId() ).append( ")" );
            }
            builder.append( "\n\n" );
            builder.append( sorted.size() ).append( " artifacts resolved." );
            builder.append( "\n\n" );
            
            getLog().info( builder );
        }
    }

    @SuppressWarnings( "unchecked" )
    private void injectLocalAsRemotes( final MavenProject project )
    {
        if ( resolveFromExistingLocalRepo )
        {
            if ( localRepositoryDirectory != null || localRepositoryProperty != null )
            {
                ArtifactRepositoryPolicy policy = new ArtifactRepositoryPolicy();
                policy.setEnabled( true );
                policy.setUpdatePolicy( "always" );

                ArtifactRepository mainLocal =
                    new DefaultArtifactRepository( "main-local", session.getLocalRepository().getUrl(),
                                                   new DefaultRepositoryLayout(), policy, policy );

                List<ArtifactRepository> rrepos = project.getRemoteArtifactRepositories();
                if ( rrepos == null )
                {
                    rrepos = new ArrayList<ArtifactRepository>();
                    project.setRemoteArtifactRepositories( rrepos );
                }

                boolean found = false;
                for ( ArtifactRepository r : rrepos )
                {
                    if ( r.getUrl().equals( mainLocal.getUrl() ) )
                    {
                        found = true;
                        break;
                    }
                }

                if ( !found )
                {
                    rrepos.add( 0, mainLocal );
                }

                List<ArtifactRepository> prepos = project.getPluginArtifactRepositories();
                if ( prepos == null )
                {
                    prepos = new ArrayList<ArtifactRepository>();
                    project.setPluginArtifactRepositories( prepos );
                }

                found = false;
                for ( ArtifactRepository r : prepos )
                {
                    if ( r.getUrl().equals( mainLocal.getUrl() ) )
                    {
                        found = true;
                        break;
                    }
                }

                if ( !found )
                {
                    prepos.add( 0, mainLocal );
                }
            }
            else
            {
                getLog().debug(
                               "Attempting to use main local repository as a remote, "
                                   + "but no alternative local repository has been specified!"
                                   + "\n\nNOT injecting.\n\n" );
            }
        }
    }

    private MavenSession selectSession()
        throws MojoExecutionException
    {
        if ( localRepositoryDirectory != null || localRepositoryProperty != null )
        {
            if ( localRepositoryDirectory != null && localRepositoryProperty != null )
            {
                getLog().warn(
                               "Both localRepositoryDirectory AND localRepositoryLocationProperty are specified. "
                                   + "Using localRepositoryDirectory." );
            }

            File localRepo;
            if ( localRepositoryDirectory != null )
            {
                localRepo = localRepositoryDirectory;
            }
            else if ( localRepositoryProperty.startsWith( "bundle:" ) )
            {
                int sepIdx = localRepositoryProperty.indexOf( ':', "bundle:".length() );
                if ( sepIdx < 0 )
                {
                    throw new MojoExecutionException( "Cannot find bundle name in property: '"
                        + localRepositoryProperty + "'\nFormat should be: 'bundle:<bundle-name>:<key>'" );
                }
                else if ( sepIdx + 1 >= localRepositoryProperty.length() )
                {
                    throw new MojoExecutionException( "Cannot find bundle value key in property: '"
                        + localRepositoryProperty + "'\nFormat should be: 'bundle:<bundle-name>:<key>'" );
                }

                String bundleName = localRepositoryProperty.substring( "bundle:".length(), sepIdx );
                String key = localRepositoryProperty.substring( sepIdx + 1 );

                ClassLoader cl = Thread.currentThread().getContextClassLoader();
                if ( bundleFile != null && bundleFile.isFile() )
                {
                    getLog().debug( "Resolving local repository path using resource bundle at: " + bundleFile );

                    File bundleDir = bundleFile.getParentFile();
                    if ( bundleDir != null )
                    {
                        try
                        {
                            cl = new URLClassLoader( new URL[] { bundleDir.toURL() }, cl );
                        }
                        catch ( MalformedURLException e )
                        {
                            throw new MojoExecutionException( "Invalid bundle location: " + bundleFile + "\nReason: "
                                + e.getMessage(), e );
                        }
                    }
                }

                getLog().debug(
                                "Loading local repository location from bundle: '" + bundleName + "', key: '" + key
                                    + "'" );

                localRepo = new File( ResourceBundle.getBundle( bundleName, Locale.getDefault(), cl ).getString( key ) );
                getLog().debug( "Found bundle value: '" + localRepo.getAbsolutePath() + "'" );
            }
            else
            {
                String path = session.getExecutionProperties().getProperty( localRepositoryProperty );
                if ( path == null )
                {
                    path = System.getProperty( localRepositoryProperty );
                }

                if ( path == null )
                {
                    throw new MojoExecutionException( "Cannot find system or Maven execution property named '"
                        + localRepositoryProperty + "'." );
                }

                getLog().debug( "Found property value for: '" + localRepositoryProperty + "' of:\n\t" + path );
                localRepo = new File( path );
            }

            ArtifactRepository localRepository;
            try
            {
                localRepository =
                    new DefaultArtifactRepository( "local", localRepo.toURL().toExternalForm(),
                                                   new DefaultRepositoryLayout() );
            }
            catch ( MalformedURLException e )
            {
                throw new MojoExecutionException( "Invalid local repository location: " + e.getMessage(), e );
            }

            return new MavenSession( session.getContainer(), session.getSettings(), localRepository,
                                     session.getEventDispatcher(), null, session.getGoals(),
                                     session.getExecutionRootDirectory(), session.getExecutionProperties(),
                                     session.getUserProperties(), session.getStartTime() );
        }

        return session;
    }

}
