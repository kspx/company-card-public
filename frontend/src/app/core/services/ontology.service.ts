import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { BehaviorSubject, Observable } from 'rxjs';
import { tap } from 'rxjs/operators';

const API = {
    ontology: 'api/ontology',
    esco: 'api/esco',
    wikidata: 'api/wikidata',
} as const;

export interface WikidataCandidate {
    qid: string;
    uri: string;
    label: string;
    description: string;
}

@Injectable({ providedIn: 'root' })
export class OntologyService {
    private readonly _instancesCache = new BehaviorSubject<Record<string, any[]>>({});
    private readonly _instancesLoading = new BehaviorSubject<Record<string, boolean>>({});
    private readonly _conceptsCache = new BehaviorSubject<Record<string, any[]>>({});
    private readonly _conceptsLoading = new BehaviorSubject<Record<string, boolean>>({});

    readonly instancesCache$ = this._instancesCache.asObservable();
    readonly conceptsCache$ = this._conceptsCache.asObservable();
    readonly conceptsLoading$ = this._conceptsLoading.asObservable();

    constructor(private http: HttpClient) {}

    loadInstancesForClass(classUri: string): void {
        this.loadIntoCache(
            `${API.ontology}/instances`, { classUri },
            this._instancesCache, this._instancesLoading, classUri
        );
    }

    loadConceptsForProperty(propertyUri: string): void {
        this.loadIntoCache(
            `${API.ontology}/concepts`, { propertyUri },
            this._conceptsCache, this._conceptsLoading, propertyUri
        );
    }

    refreshInstancesCache(classUri: string): void {
        this.loadInstancesForClass(classUri);
    }

    hasInstancesCached(classUri: string): boolean {
        return classUri in this._instancesCache.getValue();
    }

    hasConceptsCached(propertyUri: string): boolean {
        return propertyUri in this._conceptsCache.getValue();
    }

    getClasses(): Observable<any[]> {
        return this.http.get<any[]>(`${API.ontology}/classes`);
    }

    getFormSchema(classUri: string): Observable<any> {
        return this.http.get<any>(`${API.ontology}/form-schema`, { params: { classUri } });
    }

    getInstances(classUri: string): Observable<any[]> {
        return this.http.get<any[]>(`${API.ontology}/instances`, { params: { classUri } });
    }

    getInstanceDetails(instanceUri: string): Observable<any> {
        return this.http.get<any>(`${API.ontology}/instance`, { params: { uri: instanceUri } });
    }

    createInstance(payload: { class: string; values: any }): Observable<any> {
        return this.http.post<any>(`${API.ontology}/instance`, payload).pipe(
            tap(() => this.refreshInstancesCache(payload.class))
        );
    }

    updateInstance(payload: { uri: string; class: string; values: any }): Observable<any> {
        return this.http.put<any>(`${API.ontology}/instance`, payload).pipe(
            tap(() => this.refreshInstancesCache(payload.class))
        );
    }

    deleteInstance(instanceUri: string, classUri: string): Observable<any> {
        return this.http.delete<any>(`${API.ontology}/instance`, { params: { uri: instanceUri } }).pipe(
            tap(() => this.refreshInstancesCache(classUri))
        );
    }

    getConcepts(propertyUri: string): Observable<any[]> {
        return this.http.get<any[]>(`${API.ontology}/concepts`, { params: { propertyUri } });
    }

    getDerivedTeamMembershipStartDate(personUri: string, teamUri: string): Observable<{ startDate: string | null }> {
        return this.http.get<{ startDate: string | null }>(
            `${API.ontology}/derived/team-membership-start-date`,
            { params: { person: personUri, team: teamUri } }
        );
    }

    searchEscoSkills(text: string, language = 'en', limit = 20): Observable<any[]> {
        return this.http.get<any[]>(`${API.esco}/skills`, { params: { text, language, limit: limit.toString() } });
    }

    getEscoSkillLabel(uri: string, language = 'en'): Observable<any> {
        return this.http.get<any>(`${API.esco}/skill`, { params: { uri, language } });
    }

    searchEscoOccupations(text: string, language = 'en', limit = 20): Observable<any[]> {
        return this.http.get<any[]>(`${API.esco}/occupations`, { params: { text, language, limit: limit.toString() } });
    }

    getEscoOccupationLabel(uri: string, language = 'en'): Observable<any> {
        return this.http.get<any>(`${API.esco}/occupation`, { params: { uri, language } });
    }

    searchWikidata(q: string, limit = 10): Observable<WikidataCandidate[]> {
        return this.http.get<WikidataCandidate[]>(`${API.wikidata}/search`, { params: { q, limit: limit.toString() } });
    }

    enrichFromWikidata(qid: string): Observable<Record<string, any>> {
        return this.http.get<Record<string, any>>(`${API.wikidata}/enrich`, { params: { qid } });
    }

    private loadIntoCache(
        url: string,
        params: Record<string, string>,
        cache$: BehaviorSubject<Record<string, any[]>>,
        loading$: BehaviorSubject<Record<string, boolean>>,
        key: string
    ): void {
        loading$.next({ ...loading$.getValue(), [key]: true });
        this.http.get<any[]>(url, { params }).subscribe({
            next: data => cache$.next({ ...cache$.getValue(), [key]: data }),
            complete: () => loading$.next({ ...loading$.getValue(), [key]: false }),
            error: () => loading$.next({ ...loading$.getValue(), [key]: false }),
        });
    }
}
