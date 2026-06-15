import {Component, OnInit, OnDestroy, ChangeDetectorRef, ChangeDetectionStrategy} from '@angular/core';
import {Router} from '@angular/router';
import {Subscription, forkJoin, map, switchMap, of} from 'rxjs';
import {catchError} from 'rxjs/operators';
import {CommonModule} from '@angular/common';
import {MatIconModule} from '@angular/material/icon';
import {MatButtonModule} from '@angular/material/button';
import {AuthService} from '@app/core/services/auth.service';
import {OntologyService} from '@app/core/services/ontology.service';
import {finalize} from "rxjs/operators";
import {PublicNavComponent} from '@app/public-nav/public-nav.component';
import {AnonPipe} from '@app/core/pipes/anon.pipe';

const PERSON_CLASS_URI = 'https://ontology.cogni.zone/company-card#Person';

@Component({
    selector: 'app-company-card-list',
    templateUrl: './company-card-list.view.html',
    styleUrls: ['./company-card-list.view.scss'],
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [CommonModule, MatIconModule, MatButtonModule, PublicNavComponent, AnonPipe]
})
export class CompanyCardListView implements OnInit, OnDestroy {
    classes: any[] = [];
    allInstances: any[] = [];
    filteredInstances: any[] = [];
    selectedType = '';
    selectedStatus = '';
    searchText = '';
    isLoading = false;
    readonly personClassUri = PERSON_CLASS_URI;

    private countByClass = new Map<string, number>();

    get needsProfile(): boolean {
        return this.auth.role === 'USER' && !this.auth.personUri;
    }

    // Pagination
    pageSize = 10;
    currentPage: Record<string, number> = {};

    private subscriptions = new Subscription();

    constructor(
        private ontology: OntologyService,
        protected auth: AuthService,
        protected router: Router,
        private cdr: ChangeDetectorRef
    ) {}

    ngOnInit(): void {
        this.loadAll();
    }

    ngOnDestroy(): void {
        this.subscriptions.unsubscribe();
    }

    loadAll(): void {
        this.isLoading = true;
        const stream$ = this.ontology.getClasses().pipe(
            switchMap(classes => {
                this.classes = classes;
                if (!classes.length) return of([]);
                return forkJoin(classes.map(cls =>
                    this.ontology.getInstances(cls.uri).pipe(
                        map(insts => (insts || []).map(i => ({...i, classUri: cls.uri, classLabel: cls.label}))),
                        catchError(() => of([]))
                    )
                ));
            }),
            map(results => results.flat()),
            finalize(() => { this.isLoading = false; this.cdr.markForCheck(); })
        );

        this.subscriptions.add(
            stream$.subscribe(instances => {
                this.allInstances = instances;
                this.countByClass = new Map<string, number>();
                this.instancesByClass = new Map<string, any[]>();
                for (const inst of instances) {
                    this.countByClass.set(inst.classUri, (this.countByClass.get(inst.classUri) ?? 0) + 1);
                    const list = this.instancesByClass.get(inst.classUri) ?? [];
                    list.push(inst);
                    this.instancesByClass.set(inst.classUri, list);
                }
                this.applyFilter();
            })
        );
    }

    selectType(classUri: string): void {
        this.selectedType = this.selectedType === classUri ? '' : classUri;
        this.selectedStatus = '';
        this.searchText = '';
        this.applyFilter();
        this.currentPage['_filtered'] = 0;
    }

    selectStatus(status: string): void {
        this.selectedStatus = this.selectedStatus === status ? '' : status;
        this.applyFilter();
        this.currentPage['_filtered'] = 0;
    }

    onSearch(text: string): void {
        this.searchText = text;
        this.applyFilter();
        this.currentPage['_filtered'] = 0;
    }

    get statusOptions(): string[] {
        if (!this.selectedType) return [];
        const set = new Set<string>();
        for (const inst of this.allInstances) {
            if (inst.classUri === this.selectedType && inst.status) set.add(inst.status);
        }
        return [...set].sort();
    }

    get hasActiveFilter(): boolean {
        return !!this.selectedStatus || !!this.searchText;
    }

    applyFilter(): void {
        let list = this.selectedType
            ? this.allInstances.filter(inst => inst.classUri === this.selectedType)
            : this.allInstances;
        if (this.selectedStatus) {
            list = list.filter(inst => inst.status === this.selectedStatus);
        }
        if (this.searchText.trim()) {
            const q = this.searchText.trim().toLowerCase();
            list = list.filter(inst => (inst.label || '').toLowerCase().includes(q));
        }
        this.filteredInstances = list;
    }

    getCountForClass(classUri: string): number {
        return this.countByClass.get(classUri) ?? 0;
    }

    private instancesByClass = new Map<string, any[]>();

    getInstancesForClass(classUri: string): any[] {
        return this.instancesByClass.get(classUri) ?? [];
    }

    getSelectedClassLabel(): string {
        return this.classes.find(c => c.uri === this.selectedType)?.label || '';
    }

    // Pagination
    getPage(key: string): number {
        return this.currentPage[key] ?? 0;
    }

    getPagedItems(items: any[], key: string): any[] {
        const start = this.getPage(key) * this.pageSize;
        return items.slice(start, start + this.pageSize);
    }

    getTotalPages(items: any[]): number {
        if (!items?.length) return 0;
        return Math.ceil(items.length / this.pageSize);
    }

    prevPage(key: string): void {
        const p = this.getPage(key);
        if (p > 0) this.currentPage[key] = p - 1;
    }

    nextPage(key: string, items: any[]): void {
        const p = this.getPage(key);
        if ((p + 1) * this.pageSize < items.length) this.currentPage[key] = p + 1;
    }

    openInstance(inst: any): void {
        void this.router.navigate(['/company-card', encodeURIComponent(inst.classUri), encodeURIComponent(inst.uri)]);
    }

    createNew(classUri: string): void {
        void this.router.navigate(['/company-card', encodeURIComponent(classUri), 'new']);
    }
}
