import { Component, Inject, OnInit, OnDestroy, ChangeDetectionStrategy, ChangeDetectorRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatDialog, MatDialogRef, MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { Subject, Subscription } from 'rxjs';
import { debounceTime, distinctUntilChanged, finalize, switchMap } from 'rxjs/operators';
import { OntologyService, WikidataCandidate } from '@app/core/services/ontology.service';
import { FieldUtilsService } from '@app/core/services/field-utils.service';
import { FieldSearchState } from '@app/core/services/field-search-state';

export interface CreateEntityDialogData {
    classUri: string;
    classLabel: string;
    initialData?: Record<string, any>;
    lockedFields?: string[];  // prop names pre-filled from parent context — shown read-only
}

export interface CreateEntityDialogResult {
    uri: string;
    label?: string;
}

@Component({
    selector: 'app-create-entity-dialog',
    standalone: true,
    imports: [
        CommonModule, FormsModule, MatDialogModule,
        MatButtonModule, MatProgressSpinnerModule
    ],
    changeDetection: ChangeDetectionStrategy.OnPush,
    templateUrl: './create-entity-dialog.component.html',
    styleUrls: ['./create-entity-dialog.component.scss']
})
export class CreateEntityDialogComponent implements OnInit, OnDestroy {
    fields: any[] = [];
    formData: Record<string, any> = {};
    multiValueInput: Record<string, string> = {};
    skosConcepts: Record<string, any[]> = {};
    objectInstances: Record<string, any[]> = {};

    // Shared ESCO + object property search state
    search!: FieldSearchState;

    loading = true;
    submitting = false;
    error: string | null = null;
    statusMessage: string | null = null;
    statusError = false;

    wikidataQuery = '';
    wikidataResults: WikidataCandidate[] = [];
    wikidataSearching = false;
    wikidataEnriching = false;
    wikidataPicked: WikidataCandidate | null = null;
    private readonly wikidataSearch$ = new Subject<string>();

    private subscriptions = new Subscription();

    constructor(
        public dialogRef: MatDialogRef<CreateEntityDialogComponent, CreateEntityDialogResult>,
        @Inject(MAT_DIALOG_DATA) public data: CreateEntityDialogData,
        private ontology: OntologyService,
        public fu: FieldUtilsService,
        private cdr: ChangeDetectorRef,
        private dialog: MatDialog
    ) {
        this.search = new FieldSearchState(fu, ontology, this.subscriptions, cdr);
    }

    ngOnInit(): void {
        this.subscriptions.add(this.ontology.conceptsCache$.subscribe(c => {
            this.skosConcepts = c; this.cdr.markForCheck();
        }));
        this.subscriptions.add(this.ontology.instancesCache$.subscribe(c => {
            this.objectInstances = c; this.cdr.markForCheck();
        }));
        if (this.data.initialData) {
            this.formData = { ...this.data.initialData };
        }
        this.subscriptions.add(
            this.wikidataSearch$.pipe(
                debounceTime(300),
                distinctUntilChanged(),
                switchMap(q => {
                    this.wikidataSearching = true;
                    this.cdr.markForCheck();
                    return this.ontology.searchWikidata(q, 10);
                })
            ).subscribe({
                next: (results) => {
                    this.wikidataResults = results;
                    this.wikidataSearching = false;
                    this.cdr.markForCheck();
                },
                error: () => {
                    this.wikidataResults = [];
                    this.wikidataSearching = false;
                    this.cdr.markForCheck();
                }
            })
        );
        this.loadSchema();
    }

    get isOrganization(): boolean {
        return this.data.classUri.endsWith('#Organization');
    }

    onWikidataSearchChange(q: string): void {
        this.wikidataQuery = q;
        if (!q || q.trim().length < 2) {
            this.wikidataResults = [];
            this.wikidataSearching = false;
            return;
        }
        this.wikidataSearch$.next(q.trim());
    }

    selectWikidata(c: WikidataCandidate): void {
        this.wikidataPicked = c;
        this.wikidataResults = [];
        this.wikidataQuery = c.label;
        this.wikidataEnriching = true;
        this.cdr.markForCheck();
        this.subscriptions.add(
            this.ontology.enrichFromWikidata(c.qid)
                .pipe(finalize(() => { this.wikidataEnriching = false; this.cdr.markForCheck(); }))
                .subscribe({
                    next: (data) => this.applyWikidataEnrichment(data),
                    error: () => {
                        this.statusMessage = 'Failed to fetch Wikidata details';
                        this.statusError = true;
                    }
                })
        );
    }

    clearWikidataPick(): void {
        this.wikidataPicked = null;
        this.wikidataQuery = '';
        this.wikidataResults = [];
        this.cdr.markForCheck();
    }

    private applyWikidataEnrichment(data: Record<string, any>): void {
        const keyToProp: Record<string, string> = {
            legalName: 'legalName',
            altLabel: 'altLabel',
            description: 'description',
            website: 'url',
            foundingDate: 'foundingDate',
            numberOfEmployees: 'numberOfEmployees',
            companySize: 'companySize',
            sameAs: 'sameAs'
        };
        const next = { ...this.formData };
        for (const [wdKey, propName] of Object.entries(keyToProp)) {
            const value = data[wdKey];
            if (value === undefined || value === null || value === '') continue;
            if (next[propName] !== undefined && next[propName] !== '' && next[propName] !== null) continue;
            next[propName] = value;
        }
        this.formData = next;
        this.cdr.markForCheck();
    }

    ngOnDestroy(): void {
        this.subscriptions.unsubscribe();
    }

    private loadSchema(): void {
        this.loading = true;
        this.cdr.markForCheck();

        this.subscriptions.add(
            this.ontology.getFormSchema(this.data.classUri)
                .pipe(finalize(() => { this.loading = false; this.cdr.markForCheck(); }))
                .subscribe({
                    next: (schema) => {
                        this.fields = schema.fields || [];
                        this.loadRelatedData();
                        this.prefillDerivedDefaults();
                    },
                    error: () => { this.error = 'Failed to load form schema'; }
                })
        );
    }

    /**
     * Graph-derived defaults: when creating a cc:TeamMembership with both the person
     * and team already known (passed via initialData from a parent Team or Person card),
     * ask the backend for the earliest contribution date on the team's projects and
     * pre-fill schema:startDate. The field stays empty when no contribution exists —
     * we never default to today.
     */
    private prefillDerivedDefaults(): void {
        if (!this.data.classUri.endsWith('#TeamMembership')) return;
        const personUri = this.firstUri(this.formData['membershipOf']);
        const teamUri = this.firstUri(this.formData['membershipIn']);
        if (!personUri || !teamUri) return;
        if (this.formData['startDate']) return;

        this.subscriptions.add(
            this.ontology.getDerivedTeamMembershipStartDate(personUri, teamUri).subscribe({
                next: (res) => {
                    if (res.startDate) {
                        this.formData = { ...this.formData, startDate: res.startDate };
                        this.cdr.markForCheck();
                    }
                }
            })
        );
    }

    private firstUri(value: any): string | null {
        if (!value) return null;
        if (Array.isArray(value)) return value.length > 0 ? String(value[0]) : null;
        return String(value);
    }

    private loadRelatedData(): void {
        this.fields.filter(f => this.fu.isSKOS(f)).forEach(field => {
            if (!this.ontology.hasConceptsCached(field.property)) {
                this.ontology.loadConceptsForProperty(field.property);
            }
        });

        const classUris = [...new Set(
            this.fields.filter(f => this.fu.isObjectProperty(f)).map(f => f.range)
        )];
        classUris.forEach(classUri => {
            if (!this.ontology.hasInstancesCached(classUri)) {
                this.ontology.loadInstancesForClass(classUri);
            }
        });
    }

    // ── Locked field helper ──

    isLocked(f: any): boolean {
        return (this.data.lockedFields || []).includes(this.fu.getPropName(f.property));
    }

    // ── Unified value helpers ──

    getValues(f: any): string[] {
        return this.fu.getMultiValue(this.formData, this.fu.getPropName(f.property));
    }

    removeValue(f: any, uri: string): void {
        this.fu.removeValue(this.formData, this.fu.getPropName(f.property), uri, f.maxCount);
        this.cdr.markForCheck();
    }

    addMultiValue(f: any, event?: Event): void {
        if (event) event.preventDefault();
        const propName = this.fu.getPropName(f.property);
        const value = (this.multiValueInput[propName] || '').trim();
        if (!value) return;
        this.fu.selectValue(this.formData, propName, value, 0);
        this.multiValueInput[propName] = '';
        this.cdr.markForCheck();
    }

    // ── ESCO + Object search (delegated to search state) ──

    selectEsco(f: any, item: any): void { this.search.selectEsco(f, item, this.formData); }

    getFilteredObjInstances(f: any): any[] {
        return this.search.getFilteredObjInstances(f, this.objectInstances, this.getValues(f));
    }

    selectObj(f: any, inst: any): void { this.search.selectObj(f, inst, this.formData); }

    openNestedCreateDialog(f: any): void {
        const classUri = f.range;
        const nestedRef = this.dialog.open(CreateEntityDialogComponent, {
            data: { classUri, classLabel: this.fu.getLocalName(classUri) } as CreateEntityDialogData,
            width: '500px'
        });

        this.subscriptions.add(
            nestedRef.afterClosed().subscribe((result: CreateEntityDialogResult | undefined) => {
                if (!result?.uri) return;
                const newInst = { uri: result.uri, label: result.label || result.uri };
                this.objectInstances = {
                    ...this.objectInstances,
                    [classUri]: [...(this.objectInstances[classUri] || []), newInst]
                };
                this.selectObj(f, newInst);
                this.ontology.refreshInstancesCache(classUri);
            })
        );
    }

    // ── Submit ──

    onCreate(): void {
        const missing = this.fu.validateRequiredFields(this.fields, this.formData);
        if (missing.length > 0) {
            this.statusMessage = `Missing required fields: ${missing.join(', ')}`;
            this.statusError = true;
            this.cdr.markForCheck();
            return;
        }

        this.submitting = true;
        this.statusMessage = null;
        this.cdr.markForCheck();

        this.subscriptions.add(
            this.ontology.createInstance({
                class: this.data.classUri,
                values: this.formData
            })
                .pipe(finalize(() => { this.submitting = false; this.cdr.markForCheck(); }))
                .subscribe({
                    next: (response: any) => {
                        this.dialogRef.close({ uri: response.uri, label: this.computeLabel(response.uri) });
                    },
                    error: (err) => {
                        this.statusMessage = `Error: ${err.error?.error || err.message}`;
                        this.statusError = true;
                    }
                })
        );
    }

    onCancel(): void {
        this.dialogRef.close();
    }

    private computeLabel(uri: string): string {
        const parts: string[] = [];
        for (const f of this.fields) {
            if (!f.isDatatypeProperty) continue;
            const val = this.formData[this.fu.getPropName(f.property)];
            if (val && typeof val === 'string' && val.trim()) {
                parts.push(val.trim());
                if (parts.length >= 2) break;
            }
        }
        return parts.length > 0 ? parts.join(' ') : this.fu.getLocalName(uri);
    }
}
