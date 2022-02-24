/*
 * Copyright (c) 2004-2022, University of Oslo
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 * Neither the name of the HISP project nor the names of its contributors may
 * be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.hisp.dhis.tracker.preprocess;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.util.Strings;
import org.hisp.dhis.category.CategoryOption;
import org.hisp.dhis.category.CategoryOptionCombo;
import org.hisp.dhis.commons.util.TextUtils;
import org.hisp.dhis.program.Program;
import org.hisp.dhis.program.ProgramStage;
import org.hisp.dhis.tracker.TrackerIdentifier;
import org.hisp.dhis.tracker.bundle.TrackerBundle;
import org.hisp.dhis.tracker.domain.Event;
import org.hisp.dhis.tracker.preheat.TrackerPreheat;
import org.springframework.stereotype.Component;

/**
 * This preprocessor is responsible for setting the Program UID on an Event from
 * the ProgramStage if the Program is not present in the payload
 *
 * @author Enrico Colasante
 */
@Component
public class EventProgramPreProcessor
    implements BundlePreProcessor
{
    @Override
    public void process( TrackerBundle bundle )
    {
        List<Event> eventsToPreprocess = bundle.getEvents()
            .stream()
            .filter( e -> Strings.isEmpty( e.getProgram() ) || Strings.isEmpty( e.getProgramStage() ) )
            .collect( Collectors.toList() );

        for ( Event event : eventsToPreprocess )
        {
            // Extract program from program stage
            if ( Strings.isNotEmpty( event.getProgramStage() ) )
            {
                ProgramStage programStage = bundle.getPreheat().get( ProgramStage.class, event.getProgramStage() );
                if ( Objects.nonNull( programStage ) )
                {
                    // TODO remove if once metadata import is fixed
                    if ( programStage.getProgram() == null )
                    {
                        // Program stages should always have a program! Due to
                        // how metadata
                        // import is currently implemented
                        // it's possible that users run into the edge case that
                        // a program
                        // stage does not have an associated
                        // program. Tell the user it's an issue with the
                        // metadata and not
                        // the event itself. This should be
                        // fixed in the metadata import. For more see
                        // https://jira.dhis2.org/browse/DHIS2-12123
                        //
                        // PreCheckMandatoryFieldsValidationHook.validateEvent
                        // will create
                        // a validation error for this edge case
                        return;
                    }
                    event.setProgram( programStage.getProgram().getUid() );
                    bundle.getPreheat().put( TrackerIdentifier.UID, programStage.getProgram() );
                }
            }
            // If it is a program event, extract program stage from program
            else if ( Strings.isNotEmpty( event.getProgram() ) )
            {
                Program program = bundle.getPreheat().get( Program.class, event.getProgram() );
                if ( Objects.nonNull( program ) && program.isWithoutRegistration() )
                {
                    Optional<ProgramStage> programStage = program.getProgramStages().stream().findFirst();
                    if ( programStage.isPresent() )
                    {
                        event.setProgramStage( programStage.get().getUid() );
                        bundle.getPreheat().put( TrackerIdentifier.UID, programStage.get() );
                    }
                }
            }
        }
        setAttributeOptionCombo( bundle );
    }

    private void setAttributeOptionCombo( TrackerBundle bundle )
    {

        // TODO we could set event.attributeOptionCombo and
        // event.categoryOptions for any event.program.categoryCombo
        // this should simplify code down the line, as it can expect these
        // fields to be populated for valid events

        TrackerPreheat preheat = bundle.getPreheat();
        TrackerIdentifier identifier = bundle.getPreheat().getIdentifiers().getCategoryOptionComboIdScheme();

        List<Event> events = bundle.getEvents().stream()
            .filter( e -> {
                Program p = preheat.get( Program.class, e.getProgram() );
                if ( p != null && !p.getCategoryCombo().isDefault() )
                {
                    return true;
                }
                return false;
            } )
            .filter( e -> StringUtils.isBlank( e.getAttributeOptionCombo() )
                && !StringUtils.isBlank( e.getAttributeCategoryOptions() ) )
            .collect( Collectors.toList() );

        for ( Event e : events )
        {
            Program p = preheat.get( Program.class, e.getProgram() );
            Optional<String> cachedAOCId = preheat.getCachedEventAOCProgramCC( p, getCategoryOptions( preheat, e ) );
            if ( cachedAOCId.isPresent() )
            {
                CategoryOptionCombo aoc = preheat.getCategoryOptionCombo( cachedAOCId.get() );
                e.setAttributeOptionCombo( identifier.getIdentifier( aoc ) );
            }
        }
    }

    private Set<CategoryOption> getCategoryOptions( TrackerPreheat preheat, Event event )
    {
        Set<CategoryOption> categoryOptions = new HashSet<>();
        Set<String> ids = parseCategoryOptions( event );
        for ( String id : ids )
        {
            // TODO what if we cannot find the category option
            categoryOptions.add( preheat.getCategoryOption( id ) );
        }
        return categoryOptions;
    }

    private Set<String> parseCategoryOptions( Event event )
    {
        String cos = StringUtils.strip( event.getAttributeCategoryOptions() );
        if ( StringUtils.isBlank( cos ) )
        {
            return Collections.emptySet();
        }

        return TextUtils
            .splitToArray( cos, TextUtils.SEMICOLON );
    }
}