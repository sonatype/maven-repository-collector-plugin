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
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.repository.RepositoryAssembler;
import org.apache.maven.shared.repository.RepositoryAssemblyException;
import org.apache.maven.shared.repository.RepositoryBuilderConfigSource;
import org.apache.maven.shared.repository.model.DefaultRepositoryInfo;

import java.io.File;
import java.util.Map;
import java.util.Set;

/**
 * Collect dependencies, plugins, etc. into a repository directory structure.
 * 
 * @goal collect
 */
public class CollectRepositoryMojo
    extends AbstractCollectorMojo
{

    /**
     * @parameter default-value="${project.build.directory}/collected-repository"
     * @required
     */
    private File outputDirectory;

    /**
     * @parameter default-value="${localRepository}"
     */
    private ArtifactRepository localRepository;

    /**
     * @component
     */
    private RepositoryAssembler repoAssembler;

    @Override
    @SuppressWarnings( "unchecked" )
    public void collect( final MavenProject project, final Map<String, Map<String, Artifact>> pluginManagedVersions )
        throws MojoExecutionException
    {
        try
        {
            DefaultRepositoryInfo info = new DefaultRepositoryInfo();
            info.setScope( Artifact.SCOPE_TEST );

            RepositoryBuilderConfigSource configSource = new RepositoryBuilderConfigSource()
            {
                public MavenProject getProject()
                {
                    return project;
                }

                public ArtifactRepository getLocalRepository()
                {
                    return localRepository;
                }
            };

            repoAssembler.buildRemoteRepository( outputDirectory, info, configSource );
        }
        catch ( RepositoryAssemblyException e )
        {
            throw new MojoExecutionException( "Failed to collect artifacts necessary to build project.", e );
        }

        StringBuilder builder = new StringBuilder();

        builder.append( "Collected the following artifacts into: " ).append( outputDirectory.getAbsolutePath() );
        builder.append( "\n" );

        for ( Artifact artifact : (Set<Artifact>) project.getArtifacts() )
        {
            builder.append( "\n- " ).append( artifact.getId() );
        }

        builder.append( "\n\n" );
        getLog().info( builder );
    }

}
