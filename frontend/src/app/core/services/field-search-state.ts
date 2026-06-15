import { ChangeDetectorRef } from '@angular/core';
import { of, Subject, Subscription } from 'rxjs';
import { debounceTime, distinctUntilChanged, switchMap } from 'rxjs/operators';
import { OntologyService } from './ontology.service';
import { FieldUtilsService } from './field-utils.service';

/**
 * Encapsulates all ESCO / object-property search state and logic.
 * Instantiated by any component that needs search-in-field behaviour,
 * eliminating duplication between CompanyCardView and CreateEntityDialog.
 */
export class FieldSearchState {
    // ESCO
    escoSearchText: Record<string, string> = {};
    escoSearchResults: Record<string, any[]> = {};
    escoSearchLoading: Record<string, boolean> = {};
    escoLabels: Record<string, string> = {};
    private escoSearchSubjects: Record<string, Subject<string>> = {};

    // Object property
    objSearchText: Record<string, string> = {};

    constructor(
        private fu: FieldUtilsService,
        private ontology: OntologyService,
        private subscriptions: Subscription,
        private cdr: ChangeDetectorRef
    ) {}

    // ── ESCO search ──

    onEscoSearch(f: any, text: string): void {
        const propName = this.fu.getPropName(f.property);
        this.escoSearchText[propName] = text;
        const isOccupation = this.fu.isEscoOccupation(f);

        if (!this.escoSearchSubjects[propName]) {
            this.escoSearchSubjects[propName] = new Subject<string>();
            this.subscriptions.add(
                this.escoSearchSubjects[propName].pipe(
                    debounceTime(300),
                    distinctUntilChanged(),
                    switchMap(query => {
                        if (!query || query.length < 2) return of([]);
                        this.escoSearchLoading[propName] = true;
                        this.cdr.markForCheck();
                        return isOccupation
                            ? this.ontology.searchEscoOccupations(query)
                            : this.ontology.searchEscoSkills(query);
                    })
                ).subscribe(results => {
                    this.escoSearchResults[propName] = results;
                    this.escoSearchLoading[propName] = false;
                    results.forEach((r: any) => this.escoLabels[r.uri] = r.label);
                    this.cdr.markForCheck();
                })
            );
        }
        this.escoSearchSubjects[propName].next(text);
    }

    selectEsco(f: any, item: any, formData: Record<string, any>): void {
        const propName = this.fu.getPropName(f.property);
        this.escoLabels[item.uri] = item.label;
        this.fu.selectValue(formData, propName, item.uri, f.maxCount);
        this.escoSearchText[propName] = '';
        this.escoSearchResults[propName] = [];
        this.cdr.markForCheck();
    }

    getEscoLabel(uri: string): string {
        return this.escoLabels[uri] || this.fu.getLocalName(uri);
    }

    // ── Object property search ──

    onObjSearch(f: any, text: string): void {
        this.objSearchText[this.fu.getPropName(f.property)] = text;
        this.cdr.markForCheck();
    }

    getFilteredObjInstances(f: any, objectInstances: Record<string, any[]>, selectedUris: string[]): any[] {
        const text = (this.objSearchText[this.fu.getPropName(f.property)] || '').toLowerCase();
        return this.fu.filterInstances(objectInstances[f.range] || [], text, selectedUris);
    }

    selectObj(f: any, inst: any, formData: Record<string, any>): void {
        const propName = this.fu.getPropName(f.property);
        this.fu.selectValue(formData, propName, inst.uri, f.maxCount);
        this.objSearchText[propName] = '';
        this.cdr.markForCheck();
    }
}
