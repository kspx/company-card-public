import {Component, OnInit, ChangeDetectorRef, OnDestroy, ChangeDetectionStrategy} from '@angular/core';
import {ActivatedRoute, Router} from '@angular/router';
import {Subject, Subscription} from 'rxjs';
import {debounceTime, distinctUntilChanged, finalize, switchMap} from 'rxjs/operators';
import {AuthService} from "@app/core/services/auth.service";
import {OntologyService, WikidataCandidate} from '@app/core/services/ontology.service';
import {FieldUtilsService} from '@app/core/services/field-utils.service';
import {PseudonymService} from '@app/core/services/pseudonym.service';
import {FieldSearchState} from '@app/core/services/field-search-state';
import {CommonModule, Location} from '@angular/common';
import {MatIconModule} from "@angular/material/icon";
import {MatButtonModule} from "@angular/material/button";
import {MatDialog, MatDialogModule} from "@angular/material/dialog";
import {FormsModule} from "@angular/forms";
import {
  CreateEntityDialogComponent,
  CreateEntityDialogData,
  CreateEntityDialogResult
} from "./create-entity-dialog/create-entity-dialog.component";
import { PublicNavComponent } from '@app/public-nav/public-nav.component';

const PERSON_CLASS_SUFFIX = '#Person';

@Component({
  selector: 'app-company-card',
  templateUrl: './company-card.view.html',
  styleUrls: ['./company-card.view.scss'],
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [CommonModule, FormsModule, MatIconModule, MatButtonModule, MatDialogModule, PublicNavComponent]
})
export class CompanyCardView implements OnInit, OnDestroy {
  selectedClass: any = null;
  schemas: Record<string, any> = {};
  rdfResult = '';
  mode: 'edit' | 'view' = 'view';
  selectedInstance: any = null;
  formData: any = {};
  isCreatingNew = false;
  isLoading = false;

  // Caches
  objectInstances: Record<string, any[]> = {};
  skosConcepts: Record<string, any[]> = {};
  skosConceptsLoading: Record<string, boolean> = {};
  multiValueInput: Record<string, string> = {};

  // SKOS search state
  skosSearchText: Record<string, string> = {};
  private skosFilteredCache: Record<string, any[]> = {};

  // Shared ESCO + object property search state
  search!: FieldSearchState;

  wikidataQuery = '';
  wikidataResults: WikidataCandidate[] = [];
  wikidataSearching = false;
  wikidataEnriching = false;
  wikidataPicked: WikidataCandidate | null = null;
  private readonly wikidataSearch$ = new Subject<string>();

  private subscriptions = new Subscription();
  private readonly canGoBack: boolean;

  constructor(
    private ontology: OntologyService,
    protected auth: AuthService,
    public fu: FieldUtilsService,
    public pseudo: PseudonymService,
    private cdr: ChangeDetectorRef,
    private dialog: MatDialog,
    private route: ActivatedRoute,
    private router: Router,
    private location: Location
  ) {
    const previousUrl = this.router.getCurrentNavigation()?.previousNavigation?.finalUrl?.toString() ?? '';
    this.canGoBack = !!previousUrl && !previousUrl.includes('/login');
    this.search = new FieldSearchState(fu, ontology, this.subscriptions, cdr);
  }

  ngOnInit(): void {
    this.subscriptions.add(this.ontology.instancesCache$.subscribe(c => { this.objectInstances = c; this.cdr.markForCheck(); }));
    this.subscriptions.add(this.ontology.conceptsCache$.subscribe(c => { this.skosConcepts = c; this.cdr.markForCheck(); }));
    this.subscriptions.add(this.ontology.conceptsLoading$.subscribe(l => { this.skosConceptsLoading = l; this.cdr.markForCheck(); }));

    this.subscriptions.add(
      this.route.params.subscribe(params => {
        const classUri = decodeURIComponent(params['classId'] || '');
        const instanceId = params['instanceId'] || '';

        if (classUri) {
          this.selectedClass = {uri: classUri};
          this.loadSchema(classUri);

          if (instanceId === 'new') {
            this.isCreatingNew = true;
            this.mode = 'edit';
            this.formData = {};
            this.selectedInstance = null;
            this.clearWikidataPick();
          } else if (instanceId) {
            this.isCreatingNew = false;
            this.mode = 'view';
            this.selectInstance(decodeURIComponent(instanceId));
          }
        }
      })
    );

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
  }

  get isOrganizationCreate(): boolean {
    return this.isCreatingNew && !!this.selectedClass?.uri && this.selectedClass.uri.endsWith('#Organization');
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
          error: () => {}
        })
    );
  }

  clearWikidataPick(): void {
    this.wikidataPicked = null;
    this.wikidataQuery = '';
    this.wikidataResults = [];
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

  goBack(): void {
    if (!this.isCreatingNew && this.selectedInstance && this.mode === 'edit') {
      this.mode = 'view';
      this.rdfResult = '';
    } else if (this.canGoBack) {
      this.location.back();
    } else {
      void this.router.navigate(['/company-card']);
    }
  }

  navigateToInstance(classUri: string, instanceUri: string): void {
    void this.router.navigate(['/company-card', encodeURIComponent(classUri), encodeURIComponent(instanceUri)]);
  }

  // ── Schema loading ──

  loadSchema(classUri: string): void {
    this.schemas = {...this.schemas, [classUri]: {loading: true}};
    this.cdr.markForCheck();

    this.subscriptions.add(this.ontology.getFormSchema(classUri).pipe(
      finalize(() => this.cdr.markForCheck())
    ).subscribe({
      next: (schema) => {
        this.schemas = {...this.schemas, [classUri]: schema};
        this.loadRelatedData(schema.fields || []);
        // If instance data arrived before schema, ESCO labels weren't loaded yet — re-run now
        if (Object.keys(this.formData).length > 0) {
          this.loadEscoSkillLabels(this.formData);
        }
      },
      error: () => {
        this.schemas = {...this.schemas, [classUri]: {error: true}};
      }
    }));
  }

  private loadRelatedData(fields: any[]): void {
    const objClassUris = [...new Set(
      fields.filter(f => this.fu.isObjectProperty(f)).map(f => f.range)
    )];
    objClassUris.forEach(classUri => {
      if (!this.ontology.hasInstancesCached(classUri)) {
        this.ontology.loadInstancesForClass(classUri);
      }
    });

    fields.filter(f => this.fu.isSKOS(f)).forEach(field => {
      if (!this.ontology.hasConceptsCached(field.property)) {
        this.ontology.loadConceptsForProperty(field.property);
      }
    });
  }

  // ── Instance selection ──

  selectInstance(uri: string): void {
    this.rdfResult = '';
    this.isCreatingNew = false;
    this.isLoading = true;
    this.cdr.markForCheck();

    this.subscriptions.add(this.ontology.getInstanceDetails(uri).pipe(
      finalize(() => { this.isLoading = false; this.cdr.markForCheck(); })
    ).subscribe({
      next: (data: any) => {
        this.selectedInstance = {uri, ...data};
        const rawData: any = {};
        for (const key in data) {
          if (data.hasOwnProperty(key)) {
            rawData[this.fu.getPropName(key)] = data[key];
          }
        }
        this.formData = {...rawData};
        this.loadEscoSkillLabels(rawData);
      },
      error: (err) => {
        this.selectedInstance = null;
        this.formData = {};
        this.rdfResult = `✗ Error loading instance: ${err.error?.error || err.message}`;
      }
    }));
  }

  // ── Form submission ──

  submit(): void {
    if (!this.selectedClass) return;

    const missing = this.fu.validateRequiredFields(this.schemas[this.selectedClass.uri]?.fields || [], this.formData);
    if (missing.length > 0) {
      this.rdfResult = `✗ Missing required fields: ${missing.join(', ')}`;
      this.cdr.markForCheck();
      return;
    }

    this.isLoading = true;
    this.rdfResult = '';
    this.cdr.markForCheck();

    const {uri: _dropped, ...propertyValues} = this.formData;

    const obs$ = this.isCreatingNew
      ? this.ontology.createInstance({class: this.selectedClass.uri, values: propertyValues})
      : this.ontology.updateInstance({uri: this.selectedInstance.uri, class: this.selectedClass.uri, values: propertyValues});

    this.subscriptions.add(obs$.pipe(
      finalize(() => { this.isLoading = false; this.cdr.markForCheck(); })
    ).subscribe({
      next: (response: any) => {
        if (this.isCreatingNew) {
          const navigateToCard = () =>
            void this.router.navigate(['/company-card', encodeURIComponent(this.selectedClass.uri), encodeURIComponent(response.uri)]);

          const isPersonClass = this.selectedClass.uri.endsWith(PERSON_CLASS_SUFFIX);
          if (isPersonClass && this.auth.role === 'USER' && !this.auth.personUri) {
            this.auth.linkPersonUri(response.uri).subscribe({ complete: navigateToCard, error: navigateToCard });
          } else {
            navigateToCard();
          }
        } else {
          this.rdfResult = `✓ Instance updated successfully!`;
          this.mode = 'view';
          this.cdr.markForCheck();
        }
      },
      error: (err: any) => {
        this.rdfResult = `✗ Error ${this.isCreatingNew ? 'creating' : 'updating'} instance: ${err.error?.error || err.message}`;
      }
    }));
  }

  deleteInstance(): void {
    if (!this.selectedInstance) return;
    if (!confirm(`Are you sure you want to delete ${this.selectedInstance.uri}?`)) return;

    this.isLoading = true;
    this.cdr.markForCheck();

    this.subscriptions.add(
      this.ontology.deleteInstance(this.selectedInstance.uri, this.selectedClass.uri)
        .pipe(finalize(() => { this.isLoading = false; this.cdr.markForCheck(); }))
        .subscribe({
          next: () => void this.goBack(),
          error: (err) => this.rdfResult = `✗ Error: ${err.error?.error || err.message}`
        })
    );
  }

  // ── Multi-value convenience wrappers ──

  getMultiValue(f: any): string[] {
    return this.fu.getMultiValue(this.formData, this.fu.getPropName(f.property));
  }

  removeFromMultiValue(f: any, uri: string): void {
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

  // ── ESCO search ──

  onEscoSearch(f: any, text: string): void { this.search.onEscoSearch(f, text); }
  selectEscoSkill(f: any, skill: any): void { this.search.selectEsco(f, skill, this.formData); }
  getEscoSkillLabel(uri: string): string { return this.search.getEscoLabel(uri); }

  private loadEscoSkillLabels(data: any): void {
    if (!this.selectedClass || !this.schemas[this.selectedClass.uri]) return;
    const fields = this.schemas[this.selectedClass.uri].fields || [];

    fields.filter((f: any) => this.fu.isEsco(f)).forEach((f: any) => {
      const propName = this.fu.getPropName(f.property);
      const uris: string[] = Array.isArray(data[propName]) ? data[propName] : data[propName] ? [data[propName]] : [];
      uris.filter(uri => !this.search.escoLabels[uri]).forEach(uri => {
        const obs$ = this.fu.isEscoOccupation(f)
          ? this.ontology.getEscoOccupationLabel(uri)
          : this.ontology.getEscoSkillLabel(uri);
        this.subscriptions.add(obs$.subscribe({
          next: (result: any) => { this.search.escoLabels[uri] = result.label || uri; this.cdr.markForCheck(); },
          error: () => {}
        }));
      });
    });
  }

  // ── SKOS search ──

  onSkosSearch(f: any, text: string): void {
    this.skosSearchText[f.property] = text;
    if (!text) {
      this.skosFilteredCache[f.property] = [];
      this.cdr.markForCheck();
      return;
    }
    const lower = text.toLowerCase();
    const selected = this.getMultiValue(f);
    this.skosFilteredCache[f.property] = (this.skosConcepts[f.property] || [])
      .filter(c => !selected.includes(c.uri) && c.label?.toLowerCase().includes(lower))
      .slice(0, 20);
    this.cdr.markForCheck();
  }

  getFilteredConcepts(f: any): any[] {
    return this.skosFilteredCache[f.property] || [];
  }

  selectSkosConcept(f: any, concept: any): void {
    this.fu.selectValue(this.formData, this.fu.getPropName(f.property), concept.uri, f.maxCount);
    this.skosSearchText[f.property] = '';
    this.skosFilteredCache[f.property] = [];
    this.cdr.markForCheck();
  }

  getConceptLabel(uri: string, propertyUri: string): string {
    return this.fu.getConceptLabel(uri, propertyUri, this.skosConcepts);
  }

  isValueInLoadedConcepts(value: string, propertyUri: string): boolean {
    return (this.skosConcepts[propertyUri] || []).some(c => c.uri === value);
  }

  // ── Object property search ──

  onObjSearch(f: any, text: string): void { this.search.onObjSearch(f, text); }
  selectObjInstance(f: any, inst: any): void { this.search.selectObj(f, inst, this.formData); }

  getFilteredObjInstances(f: any): any[] {
    return this.search.getFilteredObjInstances(f, this.objectInstances, this.getMultiValue(f));
  }

  getInstanceLabel(uri: string): string {
    return this.fu.getInstanceLabel(uri, this.objectInstances);
  }

  hasValue(f: any): boolean {
    if (this.isFieldHiddenForPresentation(f)) return false;
    const val = this.formData[this.fu.getPropName(f.property)];
    if (Array.isArray(val)) return val.length > 0;
    return val !== null && val !== undefined && val !== '';
  }

  private static readonly PRESENTATION_HIDDEN_PERSON_FIELDS = new Set(['familyName', 'email', 'telephone', 'mbox', 'phone']);

  private get isPersonView(): boolean {
    return this.mode === 'view' && !!this.selectedClass?.uri?.endsWith(PERSON_CLASS_SUFFIX);
  }

  private isFieldHiddenForPresentation(f: any): boolean {
    return this.pseudo.enabled && this.isPersonView
      && CompanyCardView.PRESENTATION_HIDDEN_PERSON_FIELDS.has(this.fu.getPropName(f.property));
  }

  viewLiteral(f: any): string {
    const propName = this.fu.getPropName(f.property);
    if (this.pseudo.enabled && this.isPersonView && propName === 'givenName' && this.selectedInstance?.uri) {
      return this.pseudo.idFor(this.selectedInstance.uri);
    }
    return this.formData[propName];
  }

  // ── Misc ──

  canEdit(): boolean {
    if (!this.selectedClass) return false;
    return this.auth.canEdit(this.selectedClass.uri, this.selectedInstance?.uri);
  }

  toggleMode(): void {
    if (!this.canEdit()) return;
    this.mode = this.mode === 'view' ? 'edit' : 'view';
    this.rdfResult = '';
    if (this.mode === 'view' && this.selectedInstance) {
      this.selectInstance(this.selectedInstance.uri);
    }
  }

  cancel(): void {
    if (this.isCreatingNew) {
      this.goBack();
    } else if (this.selectedInstance) {
      this.mode = 'view';
      this.selectInstance(this.selectedInstance.uri);
    } else {
      this.goBack();
    }
  }

  openCreateDialog(f: any): void {
    const classUri = f.range;
    const propName = this.fu.getPropName(f.property);

    const initialData: Record<string, any> = {};
    if (f.inverseOf && this.selectedInstance?.uri) {
      initialData[this.fu.getPropName(f.inverseOf)] = this.selectedInstance.uri;
    }

    const dialogRef = this.dialog.open(CreateEntityDialogComponent, {
      data: {classUri, classLabel: this.fu.getLocalName(classUri), initialData, lockedFields: Object.keys(initialData)} as CreateEntityDialogData,
      width: '500px'
    });

    this.subscriptions.add(
      dialogRef.afterClosed().subscribe((result: CreateEntityDialogResult | undefined) => {
        if (!result?.uri) return;
        this.ontology.refreshInstancesCache(classUri);
        this.fu.selectValue(this.formData, propName, result.uri, f.maxCount);
        this.cdr.markForCheck();
      })
    );
  }
}
